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
package com.alibaba.csp.sentinel.cluster.flow.statistic.metric;

import com.alibaba.csp.sentinel.cluster.flow.statistic.data.ClusterFlowEvent;
import com.alibaba.csp.sentinel.cluster.flow.statistic.data.ClusterMetricBucket;
import com.alibaba.csp.sentinel.util.AssertUtil;

import java.util.List;

/**
 * @author Eric Zhao
 * @since 1.4.0
 */
public class ClusterMetric {

    private final ClusterMetricLeapArray metric;

    public ClusterMetric(int sampleCount, int intervalInMs) {
        AssertUtil.isTrue(sampleCount > 0, "sampleCount should be positive");
        AssertUtil.isTrue(intervalInMs > 0, "interval should be positive");
        AssertUtil.isTrue(intervalInMs % sampleCount == 0, "time span needs to be evenly divided");
        this.metric = new ClusterMetricLeapArray(sampleCount, intervalInMs);
    }

    public void add(ClusterFlowEvent event, long count) {
        metric.currentWindow().value().add(event, count);
    }

    public long getCurrentCount(ClusterFlowEvent event) {
        return metric.currentWindow().value().get(event);
    }

    /**
     * Get total sum for provided event in {@code intervalInSec}.
     *
     * @param event event to calculate
     * @return total sum for event
     */
    public long getSum(ClusterFlowEvent event) {
        metric.currentWindow();
        long sum = 0;

        List<ClusterMetricBucket> buckets = metric.values();
        for (ClusterMetricBucket bucket : buckets) {
            sum += bucket.get(event);
        }
        return sum;
    }

    /**
     * Get average count for provided event per second.
     *
     * @param event event to calculate
     * @return average count per second for event
     */
    public double getAvg(ClusterFlowEvent event) {
        return getSum(event) / metric.getIntervalInSecond();
    }

    /**
     * Try to pre-occupy upcoming buckets.
     *
     * @return time to wait for next bucket (in ms); 0 if cannot occupy next buckets
     */
    public int tryOccupyNext(ClusterFlowEvent event, int acquireCount, double threshold) {
        /**
         * event:ClusterFlowEvent.PASS
         * int acquireCount：申请的许可数
         * threshold ： 阈值
         */

        //获取当前正在访问的qps
        double latestQps = getAvg(ClusterFlowEvent.PASS);
        //是否可以抢占
        if (!canOccupy(event, acquireCount, latestQps, threshold)) {
            return 0;
        }

        metric.addOccupyPass(acquireCount);
        add(ClusterFlowEvent.WAITING, acquireCount);
        //返回一个滑动窗口的时间
        return 1000 / metric.getSampleCount();
    }

    private boolean canOccupy(ClusterFlowEvent event, int acquireCount, double latestQps, double threshold) {
        //获取未来的下一个滑动窗口的已占用的许可数
        long headPass = metric.getFirstCountOfWindow(event);

        long occupiedCount = metric.getOccupiedCount(event);
        //  bucket to occupy (= incoming bucket)
        //       ↓
        // | head bucket |    |    |    | current bucket |
        // +-------------+----+----+----+----------- ----+
        //   (headPass)
        /**
         *
         */
        return latestQps + (acquireCount + occupiedCount) - headPass <= threshold;
    }
}
