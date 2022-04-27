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

import com.alibaba.csp.sentinel.cluster.ClusterStateManager;
import com.alibaba.csp.sentinel.cluster.TokenResult;
import com.alibaba.csp.sentinel.cluster.TokenResultStatus;
import com.alibaba.csp.sentinel.cluster.TokenService;
import com.alibaba.csp.sentinel.cluster.client.TokenClientProvider;
import com.alibaba.csp.sentinel.cluster.server.EmbeddedClusterTokenServerProvider;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import com.alibaba.csp.sentinel.util.StringUtil;
import com.alibaba.csp.sentinel.util.function.Function;

import java.util.Collection;

/**
 * Rule checker for flow control rules.
 *
 * @author Eric Zhao
 */
public class FlowRuleChecker {

    public void checkFlow(Function<String, Collection<FlowRule>> ruleProvider, ResourceWrapper resource,
                          Context context, DefaultNode node, int count, boolean prioritized) throws BlockException {
        if (ruleProvider == null || resource == null) {
            return;
        }

        /**
         * 通过限流规则提供器获取与该资源相关的流控规则列表
         */
        Collection<FlowRule> rules = ruleProvider.apply(resource.getName());
        if (rules != null) {
            for (FlowRule rule : rules) {
                /**
                 * 然后遍历流控规则列表，通过调用 canPassCheck 方法来判断是否满足该规则设置的条件，
                 * 如果满足流控规则，则抛出 FlowException，即只需要满足一个即结束校验。
                 */
                if (!canPassCheck(rule, context, node, count, prioritized)) {
                    throw new FlowException(rule.getLimitApp(), rule);
                }
            }
        }
    }

    public boolean canPassCheck(/*@NonNull*/ FlowRule rule, Context context, DefaultNode node,
                                                    int acquireCount) {
        return canPassCheck(rule, context, node, acquireCount, false);
    }

    public boolean canPassCheck(/*@NonNull*/ FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                                    boolean prioritized) {
        String limitApp = rule.getLimitApp();
        //如果限流规则没有配置针对来源  则直接默认通过 该值在配置时 默认为 default 即对所有调用发起方都生效
        if (limitApp == null) {
            return true;
        }
        //集群限流模式
        /**
         * 由于网络延迟的存在，Sentinel 集群限流并未实现匀速排队流量效果控制，也没有支持冷启动，
         * 而只支持直接拒绝请求的流控效果。响应状态码 SHOULD_WAIT 并非用于实现匀速限流，
         * 而是用于实现具有优先级的请求在达到限流阈值的情况下，可试着占据下一个时间窗口的 pass 指标，
         * 如果抢占成功，则告诉限流客户端，当前请求需要休眠等待下个时间窗口的到来才可以通过。
         * Sentinel 使用提前申请在未来时间通过的方式实现优先级语意。
         */
        if (rule.isClusterMode()) {
            return passClusterCheck(rule, context, node, acquireCount, prioritized);
        }

        /**
         * 非集群限流模式
         */
        return passLocalCheck(rule, context, node, acquireCount, prioritized);
    }

    private static boolean passLocalCheck(FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                          boolean prioritized) {
        /**
         * 首先根据流控模式(strategy)选择一个合适的 Node，看到这，大家可以思考一下，这一步骤的目的，如果为空，则直接返回 true，表示放行。
         */
        Node selectedNode = selectNodeByRequesterAndStrategy(rule, context, node);
        if (selectedNode == null) {
            return true;
        }
        /**
         *  获取配置的流控效果 控制器 （1. 直接拒绝 2. 预热启动 3. 排队 4. 预热启动排队等待)
         * 调用 FlowRule 内部持有的流量控制器来判断是否符合流控规则，最终调用的是 TrafficShapingController canPass 方法
         */
        return rule.getRater().canPass(selectedNode, acquireCount, prioritized);
    }

    static Node selectReferenceNode(FlowRule rule, Context context, DefaultNode node) {

        //关联资源或入口资源
        //关联资源名称 （如果策略是关联 则是关联的资源名称，如果策略是链路 则是上下文名称）

        String refResource = rule.getRefResource();
        int strategy = rule.getStrategy();

        if (StringUtil.isEmpty(refResource)) {
            return null;
        }

        // 流控模式 = 关联，返回引用资源的ClusterNode（Context维度）
        /**
         *
         * STRATEGY_RELATE 关联其他的指定资源，如资源A想以资源B的流量状况来决定是否需要限流，这时资源A规则配置可以使用 STRATEGY_RELATE 策略
         * 如果流控模式为 RuleConstant.STRATEGY_RELATE(关联)，则从集群环境中获取对应关联资源所代表的 Node，
         * 通过(ClusterBuilderSlot会收集每一个资源的实时统计信息)
         */
        //
        if (strategy == RuleConstant.STRATEGY_RELATE) {
            //获取关联资源的集群节点
            return ClusterBuilderSlot.getClusterNode(refResource);
        }


        // 流控模式 = 链路，如果引用资源与当前上下文（EntranceNode对应资源名称）一致，返回context.curEntry.curNode，否则返回空
        // 意思是，当前Rule针对某个上下文链路（EntranceNode对应链路）才生效，返回当前Node节点（Context+Resource维度）

        /**
         *  STRATEGY_CHAIN 对指定入口的流量限流，因为流量可以有多个不同的入口（EntranceNode）
         * 如果流控模式为 RuleConstant.STRATEGY_CHAIN(调用链)，则判断当前调用上下文的入口资源与规则配置的是否一样，
         * 如果是，则返回入口资源对应的 Node，否则返回 null，注意：返回空则该条流控规则直接通过。
         */
        if (strategy == RuleConstant.STRATEGY_CHAIN) {
            if (!refResource.equals(context.getName())) {
                return null;
            }
            return node;
        }
        // No node.
        return null;
    }

    private static boolean filterOrigin(String origin) {
        // Origin cannot be `default` or `other`.
        return !RuleConstant.LIMIT_APP_DEFAULT.equals(origin) && !RuleConstant.LIMIT_APP_OTHER.equals(origin);
    }

    static Node selectNodeByRequesterAndStrategy(/*@NonNull*/ FlowRule rule, Context context, DefaultNode node) {


        /**
         *  DefaultNode：代表同一个资源在不同上下文中各自的流量情况 , 链路限流的时候使用的是这个，
         *  因为入口会被不同的线程调用，所以取的是根据contextName走的DefaultNode
         *  ClusterNode:代表同一个资源在不同上下文中总体的流量情况，默认限流和关联资源限流走的是这个，
         *  因为这里面体现的是单个资源最直接的数据。
         *  OriginNode:是一个StatisticNode类型的节点，代表了同个资源请求来源的流量情况 ，指定来源限流使用的是这个， 因为是根据不同的来源来创建OriginNode , 里面统计的也是这个来源的所有时间窗口数据
         *
         */
        String limitApp = rule.getLimitApp();
        int strategy = rule.getStrategy();
        String origin = context.getOrigin();
        /**
         * String limitApp：该条限流规则针对的调用方。
         * int strategy：该条限流规则的流控策略。
         * String origin：本次请求的调用方，从当前上下文环境中获取，例如 dubbo 服务提供者，原始调用方为 dubbo 服务提供者的 application。
         */
        //如果限流规则配置的针对的调用方与当前请求实际调用来源匹配（并且不是 default、other)时的处理逻辑
        //filterOrigin():origin既不是default 也不是other
        if (limitApp.equals(origin) && filterOrigin(origin)) {
            // 1. context.origin = rule.limitApp = xxx（非default和other）
            //如果流控模式为 RuleConstant.STRATEGY_DIRECT(直接)，则从 context 中获取源调用方所代表的 Node
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                // STRATEGY_DIRECT --- 取context.curEntry.originNode

                //ClusterBuilderSlot会设置context的originNode
                return context.getOriginNode();
            }
            // 非STRATEGY_DIRECT，获取引用的Node返回
            //配置的策略为关联或链路
            return selectReferenceNode(rule, context, node);

        } else if (RuleConstant.LIMIT_APP_DEFAULT.equals(limitApp)) {
            // 2. context.origin = rule.limitApp = default（默认）
            //如果流控规则针对的调用方(limitApp) 配置的为 default，表示对所有的调用源都生效，其获取实时统计节点(Node)的处理逻辑为：
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                // STRATEGY_DIRECT --- 取Node对应ClusterNode（Resource）维度指标返回
                //如果流控模式为 RuleConstant.STRATEGY_DIRECT，则直接获取本次调用上下文环境对应的节点的ClusterNode。
                return node.getClusterNode();
            }
            // 非STRATEGY_DIRECT，获取引用的Node返回
            return selectReferenceNode(rule, context, node);
        } else if (RuleConstant.LIMIT_APP_OTHER.equals(limitApp)
            && FlowRuleManager.isOtherOrigin(origin, rule.getResource())) {
            // 3. rule.limitApp = other && RuleManager中找不到origin+resource维度的Rule
            /**
             *   流控规则针对调用方如果设置为 other，表示针对 没有配置流控规则的资源。 流控规则是针对FlowRule里 非limitApp的其他所有调用方
             *
             * 如果流控规则针对的调用方为(other)，此时需要判断是否有针对当前的流控规则，
             * 只要存在，则这条规则对当前资源“失效”，如果针对该资源没有配置其他额外的流控规则，则获取实时统计节点(Node)的处理逻辑为：
             */
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                // STRATEGY_DIRECT --- 取context.curEntry.originNode
                //如果流控模式为 RuleConstant.STRATEGY_DIRECT(直接)，则从 context 中获取源调用方所代表的 Node。
                return context.getOriginNode();
            }
            // 非STRATEGY_DIRECT，获取引用的Node返回
            return selectReferenceNode(rule, context, node);
        }
        //如果没有选择到合适的 Node，则针对该流控规则，默认放行。
        return null;
    }

    private static boolean passClusterCheck(FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                            boolean prioritized) {
        try {
            TokenService clusterService = pickClusterService();
            if (clusterService == null) { //如果无法获取到集群限流token服务
                //如果该限流规则配置了可以退化为单机限流模式，则退化为单机限流。
                return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
            }
            //获取集群限流的流程id
            long flowId = rule.getClusterConfig().getFlowId();
            //通过 TokenService 去申请 token，这里是与单机限流模式最大的差别
            TokenResult result = clusterService.requestToken(flowId, acquireCount, prioritized);
            //解析申请token结果
            return applyTokenResult(result, rule, context, node, acquireCount, prioritized);
            // If client is absent, then fallback to local mode.
        } catch (Throwable ex) {
            RecordLog.warn("[FlowRuleChecker] Request cluster token unexpected failed", ex);
        }
        // Fallback to local flow control when token client or server for this rule is not available.
        // If fallback is not enabled, then directly pass.
        return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
    }

    private static boolean fallbackToLocalOrPass(FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                                 boolean prioritized) {
        if (rule.getClusterConfig().isFallbackToLocalWhenFail()) {
            //如果该限流规则配置了可以退化为单机限流模式，则退化为单机限流。
            return passLocalCheck(rule, context, node, acquireCount, prioritized);
        } else {
            // The rule won't be activated, just pass.
            return true;
        }
    }

    private static TokenService pickClusterService() {
        /**
         * 如果当前节点的角色为client  返回的 TokenService 为 DefaultClusterTokenClient（SPI机制）
         * 关注 DefaultClusterTokenClient#initNewConnection()
         */
        //sentinel-cluster/sentinel-cluster-client-default/src/main/resources/META-INF/services/com.alibaba.csp.sentinel.cluster.client.ClusterTokenClient
        if (ClusterStateManager.isClient()) {
            return TokenClientProvider.getClient();
        }
        //如果当前节点的角色为server 返回的 TokenService 为 ClusterTokenServer，这里使用了SPI机制，
        //sentinel-cluster/sentinel-cluster-server-default/src/main/resources/META-INF/services/com.alibaba.csp.sentinel.cluster.TokenService
        if (ClusterStateManager.isServer()) {
            return EmbeddedClusterTokenServerProvider.getServer();
        }
        return null;
    }

    private static boolean applyTokenResult(/*@NonNull*/ TokenResult result, FlowRule rule, Context context,
                                                         DefaultNode node,
                                                         int acquireCount, boolean prioritized) {
        switch (result.getStatus()) {
            case TokenResultStatus.OK:
                //申请许可成功 放行
                return true;
            case TokenResultStatus.SHOULD_WAIT:
                // Wait for next tick.
                try {
                    //休眠指定时间再放行请求
                    Thread.sleep(result.getWaitInMs());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            case TokenResultStatus.NO_RULE_EXISTS:
            case TokenResultStatus.BAD_REQUEST:
            case TokenResultStatus.FAIL:
            case TokenResultStatus.TOO_MANY_REQUEST:
                //根据规则配置的 fallbackToLocalWhenFail 是否为 true，决定是否回退为本地限流，如果需要回退为本地限流模式，则调用 passLocalCheck 方法重新判断
                return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
            case TokenResultStatus.BLOCKED:
                //抛出BlockedException
            default:
                return false;
        }
    }
}