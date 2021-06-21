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
         * 通过现楼规则提供器获取与该资源相关的l流控规则列表
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
         * 调用 FlowRule 内部持有的流量控制器来判断是否符合流控规则，最终调用的是 TrafficShapingController canPass 方法
         */
        return rule.getRater().canPass(selectedNode, acquireCount, prioritized);
    }

    static Node selectReferenceNode(FlowRule rule, Context context, DefaultNode node) {
        String refResource = rule.getRefResource();
        int strategy = rule.getStrategy();

        if (StringUtil.isEmpty(refResource)) {
            return null;
        }
        //如果流控模式为 RuleConstant.STRATEGY_RELATE(关联)，则从集群环境中获取对应关联资源所代表的 Node，
        // 通过(ClusterBuilderSlot会收集每一个资源的实时统计信息，子集群限流时详细介绍)
        if (strategy == RuleConstant.STRATEGY_RELATE) {
            return ClusterBuilderSlot.getClusterNode(refResource);
        }
        /**
         * 如果流控模式为 RuleConstant.STRATEGY_CHAIN(调用链)，则判断当前调用上下文的入口资源与规则配置的是否一样，
         * 如果是，则返回入口资源对应的 Node，否则返回 null，注意：返回空则该条流控规则直接通过。【
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

        // The limit app should not be empty.
        /**
         * String limitApp：该条限流规则针对的调用方。
         * int strategy：该条限流规则的流控策略。
         * String origin：本次请求的调用方，从当前上下文环境中获取，例如 dubbo 服务提供者，原始调用方为 dubbo 服务提供者的 application。
         */
        String limitApp = rule.getLimitApp();
        int strategy = rule.getStrategy();
        String origin = context.getOrigin();
        //如果限流规则配置的针对的调用方与当前请求实际调用来源匹配（并且不是 default、other)时的处理逻辑
        if (limitApp.equals(origin) && filterOrigin(origin)) {
            //如果流控模式为 RuleConstant.STRATEGY_DIRECT(直接)，则从 context 中获取源调用方所代表的 Node
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                // Matches limit origin, return origin statistic node.
                return context.getOriginNode();
            }

            return selectReferenceNode(rule, context, node);

        } else if (RuleConstant.LIMIT_APP_DEFAULT.equals(limitApp)) {
            //如果流控规则针对的调用方(limitApp) 配置的为 default，表示对所有的调用源都生效，其获取实时统计节点(Node)的处理逻辑为：
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                // Return the cluster node.
                //如果流控模式为 RuleConstant.STRATEGY_DIRECT，则直接获取本次调用上下文环境对应的节点的ClusterNode。
                return node.getClusterNode();
            }

            return selectReferenceNode(rule, context, node);
        } else if (RuleConstant.LIMIT_APP_OTHER.equals(limitApp)
            && FlowRuleManager.isOtherOrigin(origin, rule.getResource())) {
            /**
             *   流控规则针对调用方如果设置为 other，表示针对没有配置流控规则的资源。
             *
             * 如果流控规则针对的调用方为(other)，此时需要判断是否有针对当前的流控规则，
             * 只要存在，则这条规则对当前资源“失效”，如果针对该资源没有配置其他额外的流控规则，则获取实时统计节点(Node)的处理逻辑为：
             */
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                //如果流控模式为 RuleConstant.STRATEGY_DIRECT(直接)，则从 context 中获取源调用方所代表的 Node。
                return context.getOriginNode();
            }

            return selectReferenceNode(rule, context, node);
        }
        //如果没有选择到合适的 Node，则针对该流控规则，默认放行。
        return null;
    }

    private static boolean passClusterCheck(FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                            boolean prioritized) {
        try {
            TokenService clusterService = pickClusterService();
            if (clusterService == null) {
                return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
            }
            long flowId = rule.getClusterConfig().getFlowId();
            TokenResult result = clusterService.requestToken(flowId, acquireCount, prioritized);
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
            return passLocalCheck(rule, context, node, acquireCount, prioritized);
        } else {
            // The rule won't be activated, just pass.
            return true;
        }
    }

    private static TokenService pickClusterService() {
        if (ClusterStateManager.isClient()) {
            return TokenClientProvider.getClient();
        }
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
                return true;
            case TokenResultStatus.SHOULD_WAIT:
                // Wait for next tick.
                try {
                    Thread.sleep(result.getWaitInMs());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            case TokenResultStatus.NO_RULE_EXISTS:
            case TokenResultStatus.BAD_REQUEST:
            case TokenResultStatus.FAIL:
            case TokenResultStatus.TOO_MANY_REQUEST:
                return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
            case TokenResultStatus.BLOCKED:
            default:
                return false;
        }
    }
}