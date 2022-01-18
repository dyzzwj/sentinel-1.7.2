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
package com.alibaba.csp.sentinel.slots.block.degrade;

import com.alibaba.csp.sentinel.concurrent.NamedThreadFactory;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slots.block.AbstractRule;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p>
 * Degrade is used when the resources are in an unstable state, these resources
 * will be degraded within the next defined time window. There are two ways to
 * measure whether a resource is stable or not:
 * </p>
 * <ul>
 * <li>
 * Average response time ({@code DEGRADE_GRADE_RT}): When
 * the average RT exceeds the threshold ('count' in 'DegradeRule', in milliseconds), the
 * resource enters a quasi-degraded state. If the RT of next coming 5
 * requests still exceed this threshold, this resource will be downgraded, which
 * means that in the next time window (defined in 'timeWindow', in seconds) all the
 * access to this resource will be blocked.
 * </li>
 * <li>
 * Exception ratio: When the ratio of exception count per second and the
 * success qps exceeds the threshold, access to the resource will be blocked in
 * the coming window.
 * </li>
 * </ul>
 *
 * @author jialiang.linjl
 *
 *  降级规则
 */
public class DegradeRule extends AbstractRule {

    @SuppressWarnings("PMD.ThreadPoolCreationRule")
    private static ScheduledExecutorService pool = Executors.newScheduledThreadPool(
        Runtime.getRuntime().availableProcessors(), new NamedThreadFactory("sentinel-degrade-reset-task", true));

    public DegradeRule() {}

    public DegradeRule(String resourceName) {
        setResource(resourceName);
    }

    /**
     * RT threshold or exception ratio threshold count.
     *  上面配置规则中对应的配置值，
     *  例如当降级策略为RT时，表示设置的响应时间值
     *  当降级策略为异常比例时，表示设置的比例值
     *  当降级策略为异常数时，表示设置的异常数
     */
    private double count;

    /**
     * Degrade recover timeout (in seconds) when degradation occurs.
     *  降级发生后多久进行恢复，即结束降级，单位为毫秒。
     */
    private int timeWindow;

    /**
     * Degrade strategy (0: average RT, 1: exception ratio, 2: exception count).
     *
     *降级策略，可以选值如下：
     * 1）DEGRADE_GRADE_RT
     * 响应时间。
     * 2）DEGRADE_GRADE_EXCEPTION_RATIO
     * 异常数比例。
     * 3）DEGRADE_GRADE_EXCEPTION_COUNT
     * 异常数量。
     */
    private int grade = RuleConstant.DEGRADE_GRADE_RT;

    /**
     * Minimum number of consecutive slow requests that can trigger RT circuit breaking.
     *
     * @since 1.7.0
     * 触发 RT 响应熔断出现的最小连续慢响应请求数量。
     *
     */
    private int rtSlowRequestAmount = RuleConstant.DEGRADE_DEFAULT_SLOW_REQUEST_AMOUNT;

    /**
     * Minimum number of requests (in an active statistic time span) that can trigger circuit breaking.
     *
     * @since 1.7.0
     *  触发熔断最小的请求数量  暂不支持配置
     *
     */
    private int minRequestAmount = RuleConstant.DEGRADE_DEFAULT_MIN_REQUEST_AMOUNT;

    public int getGrade() {
        return grade;
    }

    public DegradeRule setGrade(int grade) {
        this.grade = grade;
        return this;
    }

    public double getCount() {
        return count;
    }

    public DegradeRule setCount(double count) {
        this.count = count;
        return this;
    }

    public int getTimeWindow() {
        return timeWindow;
    }

    public DegradeRule setTimeWindow(int timeWindow) {
        this.timeWindow = timeWindow;
        return this;
    }

    public int getRtSlowRequestAmount() {
        return rtSlowRequestAmount;
    }

    public DegradeRule setRtSlowRequestAmount(int rtSlowRequestAmount) {
        this.rtSlowRequestAmount = rtSlowRequestAmount;
        return this;
    }

    public int getMinRequestAmount() {
        return minRequestAmount;
    }

    public DegradeRule setMinRequestAmount(int minRequestAmount) {
        this.minRequestAmount = minRequestAmount;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        if (!super.equals(o)) { return false; }
        DegradeRule that = (DegradeRule) o;
        return Double.compare(that.count, count) == 0 &&
            timeWindow == that.timeWindow &&
            grade == that.grade &&
            rtSlowRequestAmount == that.rtSlowRequestAmount &&
            minRequestAmount == that.minRequestAmount;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + new Double(count).hashCode();
        result = 31 * result + timeWindow;
        result = 31 * result + grade;
        result = 31 * result + rtSlowRequestAmount;
        result = 31 * result + minRequestAmount;
        return result;
    }

    @Override
    public String toString() {
        return "DegradeRule{" +
            "resource=" + getResource() +
            ", grade=" + grade +
            ", count=" + count +
            ", limitApp=" + getLimitApp() +
            ", timeWindow=" + timeWindow +
            ", rtSlowRequestAmount=" + rtSlowRequestAmount +
            ", minRequestAmount=" + minRequestAmount +
            "}";
    }

    // Internal implementation (will be deprecated and moved outside).

    private AtomicLong passCount = new AtomicLong(0);

    /**
     * 当前熔断状态
     */
    private final AtomicBoolean cut = new AtomicBoolean(false);

    /**
     * 根据当前请求的情况是否触发熔断
     */
    @Override
    public boolean passCheck(Context context, DefaultNode node, int acquireCount, Object... args) {
        //如果当前正在处于熔断降级中 将直接返回false 请求将被限流
        if (cut.get()) {
            return false;
        }
        //根据资源名称获取对应的集群类节点
        ClusterNode clusterNode = ClusterBuilderSlot.getClusterNode(this.getResource());
        if (clusterNode == null) {
            return true;
        }

        /**
         * 降级策略为基于响应时间的判断规则
         */
        if (grade == RuleConstant.DEGRADE_GRADE_RT) {
            //获取节点的当前周期内（1s）平均响应时间
            double rt = clusterNode.avgRt();
            if (rt < this.count) {
                //如果当前平均响应时间小于阈值
                //重置passcount为0
                passCount.set(0);
                //放行
                return true;
            }

            // Sentinel will degrade the service only if count exceeds.
            /**
             *   如果当前平均响应时间小于 大于 阈值，但连续请求次数 小于  触发 RT 响应熔断出现的最小连续慢响应请求数量 放行
             *   只有当连续 rtSlowRequestAmount 次响应慢才会触发降级。
             *   相当于样本数值太少
             */
            if (passCount.incrementAndGet() < rtSlowRequestAmount) {
                return true;
            }
        } else if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO) {
            //降级策略为根据异常比例

            //异常qps 一个周期内（1s）总异常数 /  一个周期
            double exception = clusterNode.exceptionQps();
            //成功qps 一个周期内（1s）总成功数 /  一个周期
            double success = clusterNode.successQps();
            //总的qps
            double total = clusterNode.totalQps();
            // If total amount is less than minRequestAmount, the request will pass.
            //如果总的qps 小于 触发熔断最小的请求数量   放行
            if (total < minRequestAmount) {
                return true;
            }

            // In the same aligned statistic time window,
            // "success" (aka. completed count) = exception count + non-exception count (realSuccess)
            //如果成功数 小于 异常数 并且异常数 小于 触发熔断最小的请求数量 放行
            double realSuccess = success - exception;
            if (realSuccess <= 0 && exception < minRequestAmount) {
                return true;
            }
            //如果异常比例小于阈值 放行
            if (exception / success < count) {
                return true;
            }
        } else if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT) {
            //降级策略为根据异常数量
            //获取一分钟内的异常数量
            double exception = clusterNode.totalException();
            //如果异常数量小于阈值 放行
            if (exception < count) {
                return true;
            }
        }

        //如果符合触发熔断的规则（走到这就说明已经符合了））设置当前的熔断状态为true
        if (cut.compareAndSet(false, true)) {
            //开启调度任务 在指定时间过后进行降级恢复
            ResetTask resetTask = new ResetTask(this);
            pool.schedule(resetTask, timeWindow, TimeUnit.SECONDS);
        }

        return false;
    }

    private static final class ResetTask implements Runnable {

        private DegradeRule rule;

        ResetTask(DegradeRule rule) {
            this.rule = rule;
        }

        @Override
        public void run() {
            rule.passCount.set(0);
            rule.cut.set(false);
        }
    }
}
