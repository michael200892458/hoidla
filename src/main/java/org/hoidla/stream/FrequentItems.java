/*
 * hoidla: various algorithms for Big Data solutions
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hoidla.stream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.hoidla.util.Expirer;

/**
 * Probabilistic frequent count algorithms
 * @author pranab
 *
 */
public class FrequentItems {
	
	public static class Context {
		private String strategy;
		private int maxBucket;  
		private long expireWindow;
		private double errorLimit;
		private double errorProbLimit; 
		private int mostFrequentCount;
	}
	
	/**
	 * @param strategy
	 * @param maxBucket
	 * @param expireWindow
	 * @return
	 */
	public static FrequentItemsFinder<String> createWithStringType(FrequentItems.Context context) {
		FrequentItemsFinder<String> freqFinder = null;
		if (context.strategy.equals("MisraGries")) {
			freqFinder = new MisraGries<String>(context.maxBucket);
			if (context.expireWindow > 0) {
				freqFinder.setExpirer(new Expirer(context.expireWindow));
			}
		} else if (context.strategy.equals("CountMinSketches")) {
			freqFinder = new CountMinSketchesString(context.errorLimit, context.errorProbLimit, context.mostFrequentCount);
			if (context.expireWindow > 0) {
				freqFinder.setExpirer(new Expirer(context.expireWindow));
			}
		}
		return freqFinder;
	}

	/**
	 * @param strategy
	 * @param maxBucket
	 * @param expireWindow
	 * @return
	 */
	public static FrequentItemsFinder<Integer> createWithIntegerType(String strategy, int maxBucket, long expireWindow) {
		FrequentItemsFinder<Integer> freqFinder = null;
		if (strategy.equals("MisraGries")) {
			freqFinder = new MisraGries<Integer>(maxBucket);
			if (expireWindow > 0) {
				freqFinder.setExpirer(new Expirer(expireWindow));
			}
		}
		return freqFinder;
	}

	/**
	 * @author pranab
	 *
	 * @param <T>
	 */
	public static interface FrequentItemsFinder<T> {
		public void setExpirer(Expirer expirer);
		public void add(T value);
		public void add(T value, long timestamp);
		public Map<Integer, T> get();
	}
	
	/**
	 * @author pranab
	 *
	 * @param <T>
	 */
	public static class MisraGries<T>  implements FrequentItemsFinder<T>{
		private Map<T, Integer> buckets = new HashMap<T, Integer>(); 
		private int maxBucket;
		private List<T> toBeRemoved = new ArrayList<T>(); 
		private Map<T, List<Long>> timestampedBuckets = new HashMap<T, List<Long>>(); 
		private Expirer expirer;

		/**
		 * @param maxBucket
		 */
		public MisraGries(int maxBucket ) {
			this.maxBucket = maxBucket;
		}
		
		public void setExpirer(Expirer expirer) {
			this.expirer = expirer;
		}

		/**
		 * add item
		 * @param value
		 */
		public void add(T value) {
			Integer count = buckets.get(value);
			if (null != count) {
				//existing bucket
				buckets.put(value, count + 1);
			} else if (buckets.size() <  maxBucket) {
				//add new bucket
				buckets.put(value, 1);
			} else {
				//decrement each count
				toBeRemoved.clear();
				for (T key : buckets.keySet()) {
					int newCount = buckets.get(key) - 1;
					if (newCount > 0) {
						buckets.put(key, newCount);
					} else {
						toBeRemoved.add(key);
					}
				}
				for (T key : toBeRemoved) {
					buckets.remove(key);
				}
			}
		}

		/**
		 * add with time window based expiry
		 * @param value
		 */
		public void add(T value, long timestamp) {
			//expire old
			if (null != expirer) {
				for (T key : timestampedBuckets.keySet()) {
					List<Long> tsList = timestampedBuckets.get(key);
					expirer.expire(tsList, timestamp);
				}				
			}
			
			//add
			List<Long> timetsamps = timestampedBuckets.get(value);
			if (null != timetsamps) {
				//existing bucket
				timetsamps.add(timestamp);
			} else if (timestampedBuckets.size() <  maxBucket) {
				//add new  bucket
				timestampedBuckets.put(value, new ArrayList<Long>());
			} else {
				//remove oldest in each bucket
				toBeRemoved.clear();
				for (T key : timestampedBuckets.keySet()) {
					List<Long> tsList = timestampedBuckets.get(key);
					tsList.remove(0);
					if (tsList.isEmpty()) { 
						toBeRemoved.add(key);
					}
				}
				for (T key : toBeRemoved) {
					buckets.remove(key);
				}
			}
		}
		
		/**
		 * 
		 * @return items ordered by count
		 */
		public Map<Integer, T> get() {
			TreeMap<Integer, T> orderItems = new TreeMap<Integer, T>();
			if (timestampedBuckets.size() > 0) {
				//time windowed mode
				for (T key : timestampedBuckets.keySet()) {
					orderItems.put(timestampedBuckets.get(key).size(), key);
				}				
			} else {
				for (T key : buckets.keySet()) {
					orderItems.put(buckets.get(key), key);
				}				
			}
			return orderItems;
		}
		
	}
	
	/**
	 * @author pranab
	 *
	 */
	public static class CountMinSketchesCounter  {
		protected Expirer expirer;
		protected FrequencyDistribution.CountMinSketch minSketches;
		protected TreeMap<Integer, Object> orderedItems = new TreeMap<Integer, Object>();
		protected int mostFrequentCount;
	
		/**
		 * @param errorLimit
		 * @param errorProbLimit
		 * @param mostFrequentCount
		 */
		public CountMinSketchesCounter(double errorLimit, double errorProbLimit, int mostFrequentCount) {
			minSketches = new FrequencyDistribution.CountMinSketch(errorLimit,  errorProbLimit);
			this.mostFrequentCount = mostFrequentCount;
		}
		
		/**
		 * @param expirer
		 */
		public void setExpirer(Expirer expirer) {
			this.expirer = expirer;
		}

		/**
		 * @param value
		 */
		protected void trackCount(Object value) {
			//get frequency count and store in tree map
			int count = minSketches.getDistr(value);
			orderedItems.put(count, value);
			if (orderedItems.size() > mostFrequentCount) {
				Integer smallestKey = orderedItems.firstKey();
				orderedItems.remove(smallestKey);
			}
		}
		
	}
	
	/**
	 * @author pranab
	 *
	 */
	public static class CountMinSketchesString  extends CountMinSketchesCounter  
		implements FrequentItemsFinder<String>{

		/**
		 * @param errorLimit
		 * @param errorProbLimit
		 * @param mostFrequentCount
		 */
		public CountMinSketchesString(double errorLimit, double errorProbLimit, int mostFrequentCount) {
			super(errorLimit, errorProbLimit, mostFrequentCount);
		}

		/* (non-Javadoc)
		 * @see org.hoidla.stream.FrequentItems.FrequentItemsFinder#add(java.lang.Object)
		 */
		@Override
		public void add(String value) {
			minSketches.add(value);
			trackCount(value);
		}
		
		/* (non-Javadoc)
		 * @see org.hoidla.stream.FrequentItems.FrequentItemsFinder#add(java.lang.Object, long)
		 */
		@Override
		public void add(String value, long timestamp) {
			// TODO Auto-generated method stub
			
		}

		/* (non-Javadoc)
		 * @see org.hoidla.stream.FrequentItems.FrequentItemsFinder#get()
		 */
		@Override
		public Map<Integer, String> get() {
			TreeMap<Integer, String> orderedItems = new TreeMap<Integer, String>();
			for (Integer key :  this.orderedItems.keySet()) {
				orderedItems.put(key, (String)this.orderedItems.get(key));
			}
			return orderedItems;
		}
		
	}
}
