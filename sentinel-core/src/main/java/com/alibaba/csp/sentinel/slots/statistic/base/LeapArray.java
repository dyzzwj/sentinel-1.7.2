/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.statistic.base;

import com.alibaba.csp.sentinel.util.AssertUtil;
import com.alibaba.csp.sentinel.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <p>
 * Basic data structure for statistic metrics in Sentinel.
 * </p>
 * <p>
 * Leap array use sliding window algorithm to count data. Each bucket cover {@code windowLengthInMs} time span,
 * and the total time span is {@link #intervalInMs}, so the total bucket amount is:
 * {@code sampleCount = intervalInMs / windowLengthInMs}.
 * </p>
 *
 * @param <T> type of statistic data
 * @author jialiang.linjl
 * @author Eric Zhao
 * @author Carpenter Lee
 *
 *   滑动窗口顶层数据结构 包含一个一个的窗口数据
 *
 */
public abstract class LeapArray<T> {

    /**
     * 每一个窗口的时间间隔   windowLengthInMs = intervalInMs / sampleCount;
     */
    protected int windowLengthInMs;
    /**
     * 抽样个数，一个统计时间间隔中包含的滑动窗口个数，在 intervalInMs 相同的情况下，sampleCount 越多，抽样的统计数据就越精确，相应的需要的内存也越多。
     */
    protected int sampleCount;
    /**
     *  一个统计的时间间隔
     */
    protected int intervalInMs;

    /**
     * 一个统计时间间隔中滑动窗口的数组，从这里也可以看出，一个滑动窗口就是使用的 WindowWrap< MetricBucket > 来表示。
     */
    protected final AtomicReferenceArray<WindowWrap<T>> array;

    /**
     * The conditional (predicate) update lock is used only when current bucket is deprecated.
     */
    private final ReentrantLock updateLock = new ReentrantLock();

    /**
     * The total bucket count is: {@code sampleCount = intervalInMs / windowLengthInMs}.
     *
     * @param sampleCount  bucket count of the sliding window
     * @param intervalInMs the total time interval of this {@link LeapArray} in milliseconds
     */
    public LeapArray(int sampleCount, int intervalInMs) {
        AssertUtil.isTrue(sampleCount > 0, "bucket count is invalid: " + sampleCount);
        AssertUtil.isTrue(intervalInMs > 0, "total time interval of the sliding window should be positive");
        AssertUtil.isTrue(intervalInMs % sampleCount == 0, "time span needs to be evenly divided");

        this.windowLengthInMs = intervalInMs / sampleCount;
        this.intervalInMs = intervalInMs;
        this.sampleCount = sampleCount;

        this.array = new AtomicReferenceArray<>(sampleCount);
    }

    /**
     * Get the bucket at current timestamp.
     * 根据当前时间来确定处于哪一个滑动窗口中，即找到上图中的 WindowWrap
     */
    public WindowWrap<T> currentWindow() {
        return currentWindow(TimeUtil.currentTimeMillis());
    }

    /**
     * Create a new statistic value for bucket.
     *
     * @param timeMillis current time in milliseconds
     * @return the new empty bucket
     */
    public abstract T newEmptyBucket(long timeMillis);

    /**
     * Reset given bucket to provided start time and reset the value.
     *
     * @param startTime  the start time of the bucket in milliseconds
     * @param windowWrap current bucket
     * @return new clean bucket at given start time
     */
    protected abstract WindowWrap<T> resetWindowTo(WindowWrap<T> windowWrap, long startTime);

    /**
     * 计算当前时间对应的滑动窗口的下标
     * @param timeMillis
     * @return
     */
    private int calculateTimeIdx(/*@Valid*/ long timeMillis) {
        /**
         * 另外timeId是会随着时间的增长而增加，当前时间每增长一个windowLength的长度，
         * timeId就加1。但是idx不会增长，只会在0和1之间变换，因为array数组的长度是2，只有两个采样时间窗口。
         * 至于为什么默认只有两个采样窗口，个人觉得因为sentinel是比较轻量的框架。时间窗口中保存着很多统计数据，
         * 如果时间窗口过多的话，一方面会占用过多内存，另一方面时间窗口过多就意味着时间窗口的长度会变小，
         * 如果时间窗口长度变小，就会导致时间窗口过于频繁的滑动。
         */
        //首先用当前时间除以一个时间窗口的时间间隔，得出当前时间是多少个时间窗口的倍数，用 n 表示
        long timeId = timeMillis / windowLengthInMs;
        // Calculate current index so we can map the timestamp to the leap array.
        //然后我们可以看出从一系列时间窗口，从 0 开始，一起向前滚动 n 个得到当前时间戳代表的时间窗口的位置。
        // 现在我们要定位到这个时间窗口的位置是落在 LeapArray 中数组的下标，而一个 LeapArray 中包含 sampleCount 个元素，
        // 要得到其下标，则使用 n % sampleCount 即可
        return (int)(timeId % array.length());
    }

    protected long calculateWindowStart(/*@Valid*/ long timeMillis) {
        return timeMillis - timeMillis % windowLengthInMs;
    }

    /**
     * Get bucket item at provided timestamp.
     *
     * @param timeMillis a valid timestamp in milliseconds
     * @return current bucket item at provided timestamp if the time is valid; null if time is invalid
     *
     * 根据当前时间来确定处于哪一个滑动窗口中，即找到上图中的 WindowWrap
     */
    public WindowWrap<T> currentWindow(long timeMillis) {
        if (timeMillis < 0) {
            return null;
        }
        /**
         * 计算当前时间会落在一个采集间隔 ( LeapArray ) 中哪一个时间窗口中，
         * 即在 LeapArray 中属性 AtomicReferenceArray <WindowWrap< T>> array 的下标
         *  注意 array的长度不会变化 就是sampleCount抽样个数
         *
         *   新的窗口会覆盖（滑动）之前过期的窗口
         *
         */
        int idx = calculateTimeIdx(timeMillis);
        // Calculate current bucket start time.
        /**
         * 计算当前时间戳所在的时间窗口的开始时间，即要计算出 WindowWrap 中 windowStart 的值，
         * 其实就是要算出小于当前时间戳，并且是 windowLengthInMs 的整数倍最大的数字，
         * Sentinel 给出是算法为 ( timeMillis - timeMillis % windowLengthInMs )。
         */
        long windowStart = calculateWindowStart(timeMillis);


        /*
         * 从窗口数组中获取当前窗口项，分为三种情况
         *
         * (1) 当前窗口为空还未创建，则初始化一个
         * (2) 当前窗口的开始时间和上面计算出的窗口开始时间一致，表明当前窗口还未过期，直接返回当前窗口
         * (3) 当前窗口的开始时间 小于 上面计算出的窗口开始时间，表明当前窗口已过期，需要替换当前窗口
         */
        while (true) {  /**  死循环查找当前的时间窗口，这里之所有需要循环，是因为可能多个线程都在获取当前时间窗口。*/
            //尝试从 LeapArray 中的 WindowWrap 数组查找指定下标的元素
            WindowWrap<T> old = array.get(idx);
            if (old == null) {
                /*
                 *     B0       B1      B2    NULL      B4
                 * ||_______|_______|_______|_______|_______||___
                 * 200     400     600     800     1000    1200  timestamp
                 *                             ^
                 *                          time=888
                 *            bucket is empty, so create new and update
                 *
                 * If the old bucket is absent, then we create a new bucket at {@code windowStart},
                 * then try to update circular array via a CAS operation. Only one thread can
                 * succeed to update, while other threads yield its time slice.
                 */
                /**
                 * 如果指定下标的元素为空，则需要创建一个 WindowWrap 。
                 * 其中 WindowWrap 中的 MetricBucket 是调用其抽象方法 newEmptyBucket (timeMillis)，由不同的子类去实现。
                 */
                WindowWrap<T> window = new WindowWrap<T>(windowLengthInMs, windowStart, newEmptyBucket(timeMillis));
                /**
                 * 使用了 CAS 机制来更新 LeapArray 数组中的 元素，因为同一时间戳，可能有多个线程都在获取当前时间窗口对象，
                 * 但该时间窗口对象还未创建，这里就是避免创建多个，导致统计数据被覆盖，如果用 CAS 更新成功的线程，
                 * 则返回新建好的 WindowWrap ，CAS 设置不成功的线程继续跑这个流程，然后会进入到代码
                 */

                if (array.compareAndSet(idx, null, window)) {
                    //更新成功
                    // Successfully updated, return the created bucket.
                    return window;
                } else {
                    // Contention failed, the thread will yield its time slice to wait for bucket available.
                    //更新失败 放弃CPU执行权限 可能是有其他的线程更新了 当前线程下一次循环时old == null 不满足
                    Thread.yield();
                }
            } else if (windowStart == old.windowStart()) {
                /*
                 *     B0       B1      B2     B3      B4
                 * ||_______|_______|_______|_______|_______||___
                 * 200     400     600     800     1000    1200  timestamp
                 *                             ^
                 *                          time=888
                 *            startTime of Bucket 3: 800, so it's up-to-date
                 *
                 * If current {@code windowStart} is equal to the start timestamp of old bucket,
                 * that means the time is within the bucket, so directly return the bucket.
                 */
                //如果指定索引下的时间窗口对象不为空并判断起始时间相等 直接返回
                return old;
            } else if (windowStart > old.windowStart()) {
                /**
                 * 如果原先存在的窗口开始时间小于当前时间戳计算出来的开始时间，则表示 bucket 已被弃用
                 */
                /*
                 *   (old)
                 *             B0       B1      B2    NULL      B4
                 * |_______||_______|_______|_______|_______|_______||___
                 * ...    1200     1400    1600    1800    2000    2200  timestamp
                 *                              ^
                 *                           time=1676
                 *          startTime of Bucket 2: 400, deprecated, should be reset
                 *
                 * If the start timestamp of old bucket is behind provided time, that means
                 * the bucket is deprecated. We have to reset the bucket to current {@code windowStart}.
                 * Note that the reset and clean-up operations are hard to be atomic,
                 * so we need a update lock to guarantee the correctness of bucket update.
                 *
                 * The update lock is conditional (tiny scope) and will take effect only when
                 * bucket is deprecated, so in most cases it won't lead to performance loss.
                 */
                if (updateLock.tryLock()) {
                    try {
                        // Successfully get the update lock, now we reset the bucket.
                        /**
                         * 则需要将开始时间重置到新时间戳对应的开始时间戳
                         */
                        return resetWindowTo(old, windowStart);
                    } finally {
                        updateLock.unlock();
                    }
                } else {
                    // Contention failed, the thread will yield its time slice to wait for bucket available.
                    //获取锁失败 放弃啊CPU的执行权
                    Thread.yield();
                }
            } else if (windowStart < old.windowStart()) {
                /**
                 * 应该不会进入到该分支，因为当前时间算出来时间窗口不会比之前的小。
                 */
                // Should not go through here, as the provided time is already behind.
                return new WindowWrap<T>(windowLengthInMs, windowStart, newEmptyBucket(timeMillis));
            }
        }
    }

    /**
     * Get the previous bucket item before provided timestamp.
     *
     * @param timeMillis a valid timestamp in milliseconds
     * @return the previous bucket item before provided timestamp
     *
     *  根据当前时间获取前一个有效的滑动窗口
     *
     */
    public WindowWrap<T> getPreviousWindow(long timeMillis) {
        if (timeMillis < 0) {
            return null;
        }
        //用当前时间减去一个时间窗口间隔，然后去定位所在 LeapArray 中 数组的下标。
        int idx = calculateTimeIdx(timeMillis - windowLengthInMs);
        timeMillis = timeMillis - windowLengthInMs;
        WindowWrap<T> wrap = array.get(idx);
        //如果为空或已过期，则返回 null。
        if (wrap == null || isWindowDeprecated(wrap)) {
            return null;
        }
        //如果定位的窗口的开始时间再加上 windowLengthInMs 小于 timeMills ，说明失效，则返回 null，通常是不会走到该分支。
        if (wrap.windowStart() + windowLengthInMs < (timeMillis)) {
            return null;
        }

        return wrap;
    }

    /**
     * Get the previous bucket item for current timestamp.
     *
     * @return the previous bucket item for current timestamp
     *
     *  根据当前时间获取前一个有效的滑动窗口
     */
    public WindowWrap<T> getPreviousWindow() {
        return getPreviousWindow(TimeUtil.currentTimeMillis());
    }

    /**
     * Get statistic value from bucket for provided timestamp.
     *
     * @param timeMillis a valid timestamp in milliseconds
     * @return the statistic value if bucket for provided timestamp is up-to-date; otherwise null
     */
    public T getWindowValue(long timeMillis) {
        if (timeMillis < 0) {
            return null;
        }
        int idx = calculateTimeIdx(timeMillis);

        WindowWrap<T> bucket = array.get(idx);

        if (bucket == null || !bucket.isTimeInWindow(timeMillis)) {
            return null;
        }

        return bucket.value();
    }

    /**
     * Check if a bucket is deprecated, which means that the bucket
     * has been behind for at least an entire window time span.
     *
     * @param windowWrap a non-null bucket
     * @return true if the bucket is deprecated; otherwise false
     *  窗口的过期机制
     */

    public boolean isWindowDeprecated(/*@NonNull*/ WindowWrap<T> windowWrap) {
        return isWindowDeprecated(TimeUtil.currentTimeMillis(), windowWrap);
    }

    /**
     * 判断滑动窗口是否生效的依据是当系统时间与滑动窗口的开始时间戳的间隔大于一个采集时间，即表示过期。
     * 即从当前窗口开始，通常包含的有效窗口为 sampleCount 个有效滑动窗口
     * @param time
     * @param windowWrap
     * @return
     */
    public boolean isWindowDeprecated(long time, WindowWrap<T> windowWrap) {
        return time - windowWrap.windowStart() > intervalInMs;
    }

    /**
     * Get valid bucket list for entire sliding window.
     * The list will only contain "valid" buckets.
     *
     * @return valid bucket list for entire sliding window.
     */
    public List<WindowWrap<T>> list() {
        return list(TimeUtil.currentTimeMillis());
    }

    public List<WindowWrap<T>> list(long validTime) {
        int size = array.length();
        List<WindowWrap<T>> result = new ArrayList<WindowWrap<T>>(size);

        for (int i = 0; i < size; i++) {
            WindowWrap<T> windowWrap = array.get(i);
            if (windowWrap == null || isWindowDeprecated(validTime, windowWrap)) {
                continue;
            }
            result.add(windowWrap);
        }

        return result;
    }

    /**
     * Get all buckets for entire sliding window including deprecated buckets.
     *
     * @return all buckets for entire sliding window
     */
    public List<WindowWrap<T>> listAll() {
        int size = array.length();
        List<WindowWrap<T>> result = new ArrayList<WindowWrap<T>>(size);

        for (int i = 0; i < size; i++) {
            WindowWrap<T> windowWrap = array.get(i);
            if (windowWrap == null) {
                continue;
            }
            result.add(windowWrap);
        }

        return result;
    }

    /**
     * Get aggregated value list for entire sliding window.
     * The list will only contain value from "valid" buckets.
     *
     * @return aggregated value list for entire sliding window
     */
    public List<T> values() {
        return values(TimeUtil.currentTimeMillis());
    }

    public List<T> values(long timeMillis) {
        if (timeMillis < 0) {
            return new ArrayList<T>();
        }
        //如果是LeapArray array代表当前时间窗口的时间周期
        //如果是FutureBucketLeapArray，代表的是下一个的时间周期
        int size = array.length();
        List<T> result = new ArrayList<T>(size);

        for (int i = 0; i < size; i++) {
            WindowWrap<T> windowWrap = array.get(i);
            if (windowWrap == null || isWindowDeprecated(timeMillis, windowWrap)) {
                continue;
            }
            result.add(windowWrap.value());
        }
        return result;
    }

    /**
     * Get the valid "head" bucket of the sliding window for provided timestamp.
     * Package-private for test.
     *
     * @param timeMillis a valid timestamp in milliseconds
     * @return the "head" bucket if it exists and is valid; otherwise null
     *
     *   为提供的时间戳获取滑动窗口的有效“头”桶。
     */
    WindowWrap<T> getValidHead(long timeMillis) {
        // Calculate index for expected head time.
        //计算当前滑动窗口的下一个滑动窗口的下标
        int idx = calculateTimeIdx(timeMillis + windowLengthInMs);

        WindowWrap<T> wrap = array.get(idx);
        if (wrap == null || isWindowDeprecated(wrap)) {
            return null;
        }

        return wrap;
    }

    /**
     * Get the valid "head" bucket of the sliding window at current timestamp.
     *
     * @return the "head" bucket if it exists and is valid; otherwise null
     */
    public WindowWrap<T> getValidHead() {
        return getValidHead(TimeUtil.currentTimeMillis());
    }

    /**
     * Get sample count (total amount of buckets).
     *
     * @return sample count
     */
    public int getSampleCount() {
        return sampleCount;
    }

    /**
     * Get total interval length of the sliding window in milliseconds.
     *
     * @return interval in second
     */
    public int getIntervalInMs() {
        return intervalInMs;
    }

    /**
     * Get total interval length of the sliding window.
     *
     * @return interval in second
     */
    public double getIntervalInSecond() {
        return intervalInMs / 1000.0;
    }

    public void debug(long time) {
        StringBuilder sb = new StringBuilder();
        List<WindowWrap<T>> lists = list(time);
        sb.append("Thread_").append(Thread.currentThread().getId()).append("_");
        for (WindowWrap<T> window : lists) {
            sb.append(window.windowStart()).append(":").append(window.value().toString());
        }
        System.out.println(sb.toString());
    }

    public long currentWaiting() {
        // TODO: default method. Should remove this later.
        return 0;
    }

    public void addWaiting(long time, int acquireCount) {
        // Do nothing by default.
        throw new UnsupportedOperationException();
    }
}
