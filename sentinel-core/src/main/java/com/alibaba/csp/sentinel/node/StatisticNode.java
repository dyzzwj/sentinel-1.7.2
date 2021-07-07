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
package com.alibaba.csp.sentinel.node;

import com.alibaba.csp.sentinel.node.metric.MetricNode;
import com.alibaba.csp.sentinel.slots.statistic.base.LongAdder;
import com.alibaba.csp.sentinel.slots.statistic.metric.ArrayMetric;
import com.alibaba.csp.sentinel.slots.statistic.metric.Metric;
import com.alibaba.csp.sentinel.util.TimeUtil;
import com.alibaba.csp.sentinel.util.function.Predicate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>The statistic node keep three kinds of real-time statistics metrics:</p>
 * <ol>
 * <li>metrics in second level ({@code rollingCounterInSecond})</li>
 * <li>metrics in minute level ({@code rollingCounterInMinute})</li>
 * <li>thread count</li>
 * </ol>
 *
 * <p>
 * Sentinel use sliding window to record and count the resource statistics in real-time.
 * The sliding window infrastructure behind the {@link ArrayMetric} is {@code LeapArray}.
 * </p>
 *
 * <p>
 * case 1: When the first request comes in, Sentinel will create a new window bucket of
 * a specified time-span to store running statics, such as total response time(rt),
 * incoming request(QPS), block request(bq), etc. And the time-span is defined by sample count.
 * </p>
 * <pre>
 * 	0      100ms
 *  +-------+--→ Sliding Windows
 * 	    ^
 * 	    |
 * 	  request
 * </pre>
 * <p>
 * Sentinel use the statics of the valid buckets to decide whether this request can be passed.
 * For example, if a rule defines that only 100 requests can be passed,
 * it will sum all qps in valid buckets, and compare it to the threshold defined in rule.
 * </p>
 *
 * <p>case 2: continuous requests</p>
 * <pre>
 *  0    100ms    200ms    300ms
 *  +-------+-------+-------+-----→ Sliding Windows
 *                      ^
 *                      |
 *                   request
 * </pre>
 *
 * <p>case 3: requests keeps coming, and previous buckets become invalid</p>
 * <pre>
 *  0    100ms    200ms	  800ms	   900ms  1000ms    1300ms
 *  +-------+-------+ ...... +-------+-------+ ...... +-------+-----→ Sliding Windows
 *                                                      ^
 *                                                      |
 *                                                    request
 * </pre>
 *
 * <p>The sliding window should become:</p>
 * <pre>
 * 300ms     800ms  900ms  1000ms  1300ms
 *  + ...... +-------+ ...... +-------+-----→ Sliding Windows
 *                                                      ^
 *                                                      |
 *                                                    request
 * </pre>
 *
 * @author qinan.qn
 * @author jialiang.linjl
 *  统计节点
 *  实时统计信息的默认实现类
 */
public class StatisticNode implements Node {

     /**
     * 每秒的实时统计信息 使用ArrayMetric实现 即基于滑动窗口实现 默认1s 采样 2次。即一个统计周期中包含两个滑动窗口。
     */
    private transient volatile Metric rollingCounterInSecond = new ArrayMetric(SampleCountProperty.SAMPLE_COUNT,
        IntervalProperty.INTERVAL);

    /**
     * 每分钟实时统计信息，同样使用 ArrayMetric 实现，即基于滑动窗口实现。每1分钟，抽样60次，即包含60个滑动窗口，每一个窗口的时间间隔为 1s 。
     */
    private transient Metric rollingCounterInMinute = new ArrayMetric(60, 60 * 1000, false);

    /**
     *  当前线程计数器 统计并发使用的线程数
     *  负责统计并行占用的线程数，用于实现信号量隔离，按资源所能并发占用的最大线程数实现限流。
     *  当接收到一个请求就将 curThreadNum 自增 1，当处理完请求时就将 curThreadNum 自减一，
     *  如果同时处理 10 个请求，那么 curThreadNum 的值就为 10
     *
     */
    private LongAdder curThreadNum = new LongAdder();

    /**
     * 上一次获取资源的有效统计数据的时间，即调用 Node 的 metrics() 方法的时间。
     */
    private long lastFetchTime = -1;


    /**
     * 由于 Sentienl 基于滑动窗口来实时收集统计信息，并存储在内存中，并随着时间的推移，旧的滑动窗口将失效，
     * 故需要提供一个方法，及时将所有的统计信息进行汇总输出，供监控客户端定时拉取，转储都其他客户端，
     * 例如数据库，方便监控数据的可视化，这也通常是中间件用于监控指标的监控与采集的通用设计方法。
     * @return
     */
    @Override
    public Map<Long, MetricNode> metrics() {
        // The fetch operation is thread-safe under a single-thread scheduler pool.
        long currentTime = TimeUtil.currentTimeMillis();
        //获取当前时间对应的滑动窗口的开始时间
        currentTime = currentTime - currentTime % 1000;
        Map<Long, MetricNode> metrics = new ConcurrentHashMap<>();
        //获取一分钟内的所有滑动窗口中的统计数据，使用 MetricNode 表示。
        List<MetricNode> nodesOfEverySecond = rollingCounterInMinute.details();
        long newLastFetchTime = lastFetchTime;
        // Iterate metrics of all resources, filter valid metrics (not-empty and up-to-date).
        for (MetricNode node : nodesOfEverySecond) {
            /**遍历所有节点，刷选出不是当前滑动窗口的所有数据(即“过去”的统计数据)。这里的重点是方法：isNodeInTime*/
            if (isNodeInTime(node, currentTime) && isValidMetricNode(node)) {
                metrics.put(node.getTimestamp(), node);
                newLastFetchTime = Math.max(newLastFetchTime, node.getTimestamp());
            }
        }
        lastFetchTime = newLastFetchTime;

        return metrics;
    }

    @Override
    public List<MetricNode> rawMetricsInMin(Predicate<Long> timePredicate) {
        return rollingCounterInMinute.detailsOnCondition(timePredicate);
    }

    /**
     * 这里只刷选出不是当前窗口的数据，即 metrics 方法返回的是“过去”的统计数据。
     */
    private boolean isNodeInTime(MetricNode node, long currentTime) {
        return node.getTimestamp() > lastFetchTime && node.getTimestamp() < currentTime;
    }

    private boolean isValidMetricNode(MetricNode node) {
        return node.getPassQps() > 0 || node.getBlockQps() > 0 || node.getSuccessQps() > 0
            || node.getExceptionQps() > 0 || node.getRt() > 0 || node.getOccupiedPassQps() > 0;
    }

    @Override
    public void reset() {
        rollingCounterInSecond = new ArrayMetric(SampleCountProperty.SAMPLE_COUNT, IntervalProperty.INTERVAL);
    }

    /**
     * 获取当前时间戳的总请求数  获取分钟级时间窗口中的统计信息。
     * @return
     */
    @Override
    public long totalRequest() {
        return rollingCounterInMinute.pass() + rollingCounterInMinute.block();
    }

    @Override
    public long blockRequest() {
        return rollingCounterInMinute.block();
    }

    @Override
    public double blockQps() {
        return rollingCounterInSecond.block() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public double previousBlockQps() {
        return this.rollingCounterInMinute.previousWindowBlock();
    }

    @Override
    public double previousPassQps() {
        return this.rollingCounterInMinute.previousWindowPass();
    }

    @Override
    public double totalQps() {
        return passQps() + blockQps();
    }

    @Override
    public long totalSuccess() {
        return rollingCounterInMinute.success();
    }

    @Override
    public double exceptionQps() {
        return rollingCounterInSecond.exception() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public long totalException() {
        return rollingCounterInMinute.exception();
    }

    @Override
    public double passQps() {
        return rollingCounterInSecond.pass() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public long totalPass() {
        return rollingCounterInMinute.pass();
    }

    /**
     *  成功TPS 用秒级统计滑动窗口中统计的个数 / 窗口的间隔(s) 得出其tps 即抽样个数越大 统计越精确
     * @return
     */
    @Override
    public double successQps() {
        return rollingCounterInSecond.success() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public double maxSuccessQps() {
        return (double) rollingCounterInSecond.maxSuccess() * rollingCounterInSecond.getSampleCount()
                / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public double occupiedPassQps() {
        return rollingCounterInSecond.occupiedPass() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public double avgRt() {
        //当前周期内(1s)成功数
        long successCount = rollingCounterInSecond.success();
        if (successCount == 0) {
            return 0;
        }

        //当前周期内（1s）总的响应时间  / 当前周期内(1s)成功数
        return rollingCounterInSecond.rt() * 1.0 / successCount;
    }

    @Override
    public double minRt() {
        return rollingCounterInSecond.minRt();
    }

    @Override
    public int curThreadNum() {
        return (int)curThreadNum.sum();
    }

    /**
     * 增加通过请求数量 即将实时调用信息向滑动窗口中进行实时统计
     * addPassRequest 即报告成功的通过数量。
     * 就是分别调用 秒级、分钟即对应的滑动窗口中添加数量，然后限流规则、熔断规则将基于滑动窗口中的值进行计算
     * @param count count to add pass
     */
    @Override
    public void addPassRequest(int count) {
        rollingCounterInSecond.addPass(count);
        rollingCounterInMinute.addPass(count);
    }

    @Override
    public void addRtAndSuccess(long rt, int successCount) {
        rollingCounterInSecond.addSuccess(successCount);
        rollingCounterInSecond.addRT(rt);

        rollingCounterInMinute.addSuccess(successCount);
        rollingCounterInMinute.addRT(rt);
    }

    @Override
    public void increaseBlockQps(int count) {
        rollingCounterInSecond.addBlock(count);
        rollingCounterInMinute.addBlock(count);
    }

    @Override
    public void increaseExceptionQps(int count) {
        rollingCounterInSecond.addException(count);
        rollingCounterInMinute.addException(count);
    }

    @Override
    public void increaseThreadNum() {
        curThreadNum.increment();
    }

    @Override
    public void decreaseThreadNum() {
        curThreadNum.decrement();
    }

    @Override
    public void debug() {
        rollingCounterInSecond.debug();
    }

    @Override
    public long tryOccupyNext(long currentTime, int acquireCount, double threshold) {
        /**
         *  currentTime：当前时间
         *  acquireCount：需要的令牌数
         *  threshold：总令牌数
         */
        //下一个周期内允许的总令牌数
        double maxCount = threshold * IntervalProperty.INTERVAL / 1000;
        //获取下一个周期内 所有滑动窗口已经被占用的令牌数
        long currentBorrow = rollingCounterInSecond.waiting();

        //下一个统计的事件间隔内（周期）的所有令牌已全被占用
        if (currentBorrow >= maxCount) {
            return OccupyTimeoutProperty.getOccupyTimeout();
        }

       // 下一个统计的事件间隔内（周期）还有令牌

        //每个窗口的时间间隔
        int windowLength = IntervalProperty.INTERVAL / SampleCountProperty.SAMPLE_COUNT;
        //  currentTime - currentTime % windowLength ：滑动窗口的起始位置
        //上一个滑动窗口的开始时间
        // currentTime - currentTime % windowLength：当前滑动窗口的开始时间
        long earliestTime = currentTime - currentTime % windowLength + windowLength - IntervalProperty.INTERVAL;

        int idx = 0;
        /*
         * Note: here {@code currentPass} may be less than it really is NOW, because time difference
         * since call rollingCounterInSecond.pass(). So in high concurrency, the following code may
         * lead more tokens be borrowed.
         */

        //当前周期内 已发放的令牌
        long currentPass = rollingCounterInSecond.pass();
        //如果下一个滑动窗口的结束时间 < 当前时间
        while (earliestTime < currentTime) {
            //当前时间到下一个滑动窗口的间隔  idx表示到下第几个窗口的时间间隔
            long waitInMs = idx * windowLength + windowLength - currentTime % windowLength;
            //OccupyTimeoutProperty.getOccupyTimeout : 500
            if (waitInMs >= OccupyTimeoutProperty.getOccupyTimeout()) {
                break;
            }
            // 上一个滑动窗口 已发放的令牌
            long windowPass = rollingCounterInSecond.getWindowPass(earliestTime);
            //如果当前时间间隔内已发放的令牌 + 下一个周期内 所有滑动窗口已经被占用的令牌数 + 需要的令牌数 - 上一个滑动窗口 已发放的令牌 < 下一个统计的时间间隔内总的令牌数
            if (currentPass + currentBorrow + acquireCount - windowPass <= maxCount) {
                //返回当前时间到下一个滑动窗口的间隔
                return waitInMs;
            }
            earliestTime += windowLength;
            currentPass -= windowPass;
            idx++;
        }

        return OccupyTimeoutProperty.getOccupyTimeout();
    }

    @Override
    public long waiting() {
        return rollingCounterInSecond.waiting();
    }

    @Override
    public void addWaitingRequest(long futureTime, int acquireCount) {
        rollingCounterInSecond.addWaiting(futureTime, acquireCount);
    }

    @Override
    public void addOccupiedPass(int acquireCount) {
        rollingCounterInMinute.addOccupiedPass(acquireCount);
        rollingCounterInMinute.addPass(acquireCount);
    }
}
