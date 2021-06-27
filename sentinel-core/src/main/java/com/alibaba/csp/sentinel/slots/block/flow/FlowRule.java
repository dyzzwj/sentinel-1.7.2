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
package com.alibaba.csp.sentinel.slots.block.flow;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slots.block.AbstractRule;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;

/**
 * <p>
 * Each flow rule is mainly composed of three factors: <strong>grade</strong>,
 * <strong>strategy</strong> and <strong>controlBehavior</strong>:
 * </p>
 * <ul>
 *     <li>The {@link #grade} represents the threshold type of flow control (by QPS or thread count).</li>
 *     <li>The {@link #strategy} represents the strategy based on invocation relation.</li>
 *     <li>The {@link #controlBehavior} represents the QPS shaping behavior (actions on incoming request when QPS
 *     exceeds the threshold).</li>
 * </ul>
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 */
public class FlowRule extends AbstractRule {

    public FlowRule() {
        super();
        setLimitApp(RuleConstant.LIMIT_APP_DEFAULT);
    }

    public FlowRule(String resourceName) {
        super();
        setResource(resourceName);

        setLimitApp(RuleConstant.LIMIT_APP_DEFAULT);
    }

    /**
     * The threshold type of flow control (0: thread count, 1: QPS).
     *
     *  流量控制的阈值类型，目前支持 QPS 与 并发线程数，对应 【新增流控规则界面】的阔值类型。
     *  默认是qps
     */
    private int grade = RuleConstant.FLOW_GRADE_QPS;

    /**
     * Flow control threshold count.
     *
     * 流量控制阈值
     */
    private double count;

    /**
     * Flow control strategy based on invocation chain.
     *
     * {@link RuleConstant#STRATEGY_DIRECT} for direct flow control (by origin);
     * {@link RuleConstant#STRATEGY_RELATE} for relevant flow control (with relevant resource);
     * {@link RuleConstant#STRATEGY_CHAIN} for chain flow control (by entrance resource).
     *  基于调用链的流量控制策略  对应【新增流控规则界面】的流控模式
     *
     *   可选值：STRATEGY_DIRECT(根据调用方限流策略)、STRATEGY_RELATE(关联流量限
     * 流策略)、STRATEGY_CHAIN(根据调用链入口限流策略)
     *
     */
    private int strategy = RuleConstant.STRATEGY_DIRECT;

    /**
     * Reference resource in flow control with relevant resource or context.
     *
     *  关联资源或入口资源，当流控模式为关联或链路时配置的关联资源或入口资源，对应【新增流控规则界面】的【入口资源】
     */
    private String refResource;

    /**
     * Rate limiter control behavior.
     * 0. default(reject directly), 1. warm up, 2. rate limiter, 3. warm up + rate limiter
     *
     *  流量控制后的采取的行为，其可选取值在本文开头部分有详细介绍，对应【新增流控规则界面】的流控效果。
     *
     *   CONTROL_BEHAVIOR_DEFAULT(直接拒绝)、CONTROL_BEHAVIOR_WARM_UP(预热)、CONTROL_BEHAVIOR_RATE_LIMITER(匀速排队)、
     * CONTROL_BEHAVIOR_WARM_UP_RATE_LIMITER(预热与匀速排队)。
     */
    private int controlBehavior = RuleConstant.CONTROL_BEHAVIOR_DEFAULT;

    /**
     * 预热时间，如果 controlBehavior 设置为预热(warm up)时，可以配置其预热时间，
     * 在【新增流控规则界面】中选择 warm up 类型后，会增加一行，供用户配置，默认值 10s。
     */
    private int warmUpPeriodSec = 10;

    /**
     * Max queueing time in rate limiter behavior.
     *
     *  最大超时时间，如果 controlBehavior 设置为排队等待时，等待的最大超时时间，默认为500ms。
     */
    private int maxQueueingTimeMs = 500;

    /**
     * 是否是集群限流模式，对应【新增流控规则界面】的是否集群。
     */
    private boolean clusterMode;
    /**
     * Flow rule config for cluster mode.
     *
     *  集群扩容相关配置
     */
    private ClusterFlowConfig clusterConfig;

    /**
     * The traffic shaping (throttling) controller.
     *  根据controlBehavior生成对应的实现类
     *  一个FlowRule对应一个TrafficShapingController
     *
     */
    private TrafficShapingController controller;

    public int getControlBehavior() {
        return controlBehavior;
    }

    public FlowRule setControlBehavior(int controlBehavior) {
        this.controlBehavior = controlBehavior;
        return this;
    }

    public int getMaxQueueingTimeMs() {
        return maxQueueingTimeMs;
    }

    public FlowRule setMaxQueueingTimeMs(int maxQueueingTimeMs) {
        this.maxQueueingTimeMs = maxQueueingTimeMs;
        return this;
    }

    FlowRule setRater(TrafficShapingController rater) {
        this.controller = rater;
        return this;
    }

    TrafficShapingController getRater() {
        return controller;
    }

    public int getWarmUpPeriodSec() {
        return warmUpPeriodSec;
    }

    public FlowRule setWarmUpPeriodSec(int warmUpPeriodSec) {
        this.warmUpPeriodSec = warmUpPeriodSec;
        return this;
    }

    public int getGrade() {
        return grade;
    }

    public FlowRule setGrade(int grade) {
        this.grade = grade;
        return this;
    }

    public double getCount() {
        return count;
    }

    public FlowRule setCount(double count) {
        this.count = count;
        return this;
    }

    public int getStrategy() {
        return strategy;
    }

    public FlowRule setStrategy(int strategy) {
        this.strategy = strategy;
        return this;
    }

    public String getRefResource() {
        return refResource;
    }

    public FlowRule setRefResource(String refResource) {
        this.refResource = refResource;
        return this;
    }

    public boolean isClusterMode() {
        return clusterMode;
    }

    public FlowRule setClusterMode(boolean clusterMode) {
        this.clusterMode = clusterMode;
        return this;
    }

    public ClusterFlowConfig getClusterConfig() {
        return clusterConfig;
    }

    public FlowRule setClusterConfig(ClusterFlowConfig clusterConfig) {
        this.clusterConfig = clusterConfig;
        return this;
    }

    @Override
    public boolean passCheck(Context context, DefaultNode node, int acquireCount, Object... args) {
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        if (!super.equals(o)) { return false; }

        FlowRule rule = (FlowRule)o;

        if (grade != rule.grade) { return false; }
        if (Double.compare(rule.count, count) != 0) { return false; }
        if (strategy != rule.strategy) { return false; }
        if (controlBehavior != rule.controlBehavior) { return false; }
        if (warmUpPeriodSec != rule.warmUpPeriodSec) { return false; }
        if (maxQueueingTimeMs != rule.maxQueueingTimeMs) { return false; }
        if (clusterMode != rule.clusterMode) { return false; }
        if (refResource != null ? !refResource.equals(rule.refResource) : rule.refResource != null) { return false; }
        return clusterConfig != null ? clusterConfig.equals(rule.clusterConfig) : rule.clusterConfig == null;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        long temp;
        result = 31 * result + grade;
        temp = Double.doubleToLongBits(count);
        result = 31 * result + (int)(temp ^ (temp >>> 32));
        result = 31 * result + strategy;
        result = 31 * result + (refResource != null ? refResource.hashCode() : 0);
        result = 31 * result + controlBehavior;
        result = 31 * result + warmUpPeriodSec;
        result = 31 * result + maxQueueingTimeMs;
        result = 31 * result + (clusterMode ? 1 : 0);
        result = 31 * result + (clusterConfig != null ? clusterConfig.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "FlowRule{" +
            "resource=" + getResource() +
            ", limitApp=" + getLimitApp() +
            ", grade=" + grade +
            ", count=" + count +
            ", strategy=" + strategy +
            ", refResource=" + refResource +
            ", controlBehavior=" + controlBehavior +
            ", warmUpPeriodSec=" + warmUpPeriodSec +
            ", maxQueueingTimeMs=" + maxQueueingTimeMs +
            ", clusterMode=" + clusterMode +
            ", clusterConfig=" + clusterConfig +
            ", controller=" + controller +
            '}';
    }
}
