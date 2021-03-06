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
package com.alibaba.csp.sentinel.slots.statistic;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.config.SentinelConfig;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotEntryCallback;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotExitCallback;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.PriorityWaitException;
import com.alibaba.csp.sentinel.spi.SpiOrder;
import com.alibaba.csp.sentinel.util.TimeUtil;

import java.util.Collection;

/**
 * <p>
 * A processor slot that dedicates to real time statistics.
 * When entering this slot, we need to separately count the following
 * information:
 * <ul>
 * <li>{@link ClusterNode}: total statistics of a cluster node of the resource ID.</li>
 * <li>Origin node: statistics of a cluster node from different callers/origins.</li>
 * <li>{@link DefaultNode}: statistics for specific resource name in the specific context.</li>
 * <li>Finally, the sum statistics of all entrances.</li>
 * </ul>
 * </p>
 *
 *
 *   StatisticSlot，专用于实时统计的 slot。在进入一个资源时，在执行 Sentienl 的处理链条中会进入到该 slot 中，需要完成如下计算任务：
 *
 * 集群维度计算资源的总统计信息，用于集群限流，后续文章将详细探讨。
 * 来自不同调用方/来源的群集节点的统计信息。
 * 特定调用上下文环境的统计信息。
 * 统计所有入口的统计信息。
 *
 *
 */
@SpiOrder(-7000)
public class StatisticSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count,
                      boolean prioritized, Object... args) throws Throwable {
        try {
            // Do some checking.
            /**
             * 首先调用 fireEntry，先调用 Sentinel Slot Chain 中其他的处理器，执行完其他处理器的逻辑，例如 FlowSlot、DegradeSlot，因为 StatisticSlot 的职责是收集统计信息。
             */
            fireEntry(context, resourceWrapper, node, count, prioritized, args);

            /**
             * 统计数据ThreadNum++ passRequest++
             *  2-1. DefaultNode Resource+Context维度  ClusterNode Resource 维度
             *
             * 如果后续处理器成功执行，即能通过SlotChain中后面的Slot的entry方法，说明没有被限流或降级
             * 则将正在执行线程数统计指标加一，并将通过的请求数量指标增加对应的值。
             *
             * 每个节点关联一个资源，资源的实时统计信息就存储在 Node 中，故该部分也是调用 DefaultNode 的相关方法来改变线程数等。
             */
            // Request passed, add thread count and pass count.
            node.increaseThreadNum();
            node.addPassRequest(count);

            /**
             *  // 2-2. origin ClusterNode  origin+Resource维度
             * 如果上下文环境中保存的调用源头（调用方）的节点信息不为空 则更新该节点的统计数据 ：线程数与通过数量
             *
             *  ClusterBuilderSlot中设置的OriginNode
             */
            if (context.getCurEntry().getOriginNode() != null) {
                // Add count for origin node.
                context.getCurEntry().getOriginNode().increaseThreadNum();
                context.getCurEntry().getOriginNode().addPassRequest(count);
            }
            /**
             *  // 2-3 全局入口流量统计
             *  如果资源的进入类型为 EntryType.IN，表示入站流量，更新入站全局统计数据(集群范围 ClusterNode)。
             */
            if (resourceWrapper.getEntryType() == EntryType.IN) {
                // Add count for global inbound entry node for global statistics.
                Constants.ENTRY_NODE.increaseThreadNum();
                Constants.ENTRY_NODE.addPassRequest(count);
            }
            // 3. 给用户的扩展点，可以通过SPI加载
            //执行注册的进入Handler，可以通过 StatisticSlotCallbackRegistry 的 addEntryCallback 注册相关监听器。
            // Handle pass event with registered entry callback handlers.
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onPass(context, resourceWrapper, node, count, args);
            }
        } catch (PriorityWaitException ex) {
            /**    // 这是流控规则才会抛出的异常
             * 如果捕获到 PriorityWaitException ，则认为是等待过一定时间，但最终还是算通过，
             * 只需增加线程的个数，但无需增加节点通过的数量，具体原因我们在详细分析限流部分时会重点讨论，也会再次阐述 PriorityWaitException 的含义
             */
            node.increaseThreadNum();
            if (context.getCurEntry().getOriginNode() != null) {
                // Add count for origin node.
                context.getCurEntry().getOriginNode().increaseThreadNum();
            }

            if (resourceWrapper.getEntryType() == EntryType.IN) {
                // Add count for global inbound entry node for global statistics.
                Constants.ENTRY_NODE.increaseThreadNum();
            }
            // Handle pass event with registered entry callback handlers.
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onPass(context, resourceWrapper, node, count, args);
            }
        } catch (BlockException e) {
            // 1. 设置BlockError到上下文中
            // Blocked, set block exception to current entry.
            context.getCurEntry().setError(e);
            /**
             *  // 2. 统计数据 BlockQps++
             *  // 2-1. DefaultNode Resource+Context维度  ClusterNode Resource 维度
             * 如果捕获到 BlockException，则主要增加阻塞的数量。
             */
            // Add block count.
            node.increaseBlockQps(count);
            // 2-2 origin ClusterNode  origin+Resource维度
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().increaseBlockQps(count);
            }

            // 2-3 全局入口流量统计
            if (resourceWrapper.getEntryType() == EntryType.IN) {
                // Add count for global inbound entry node for global statistics.
                Constants.ENTRY_NODE.increaseBlockQps(count);
            }

            // 3. 给用户的扩展点，可以通过SPI加载
            // Handle block event with registered entry callback handlers.
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onBlocked(e, context, resourceWrapper, node, count, args);
            }

            throw e;
        } catch (Throwable e) {
            // Unexpected error, set error to current entry.
            context.getCurEntry().setError(e);
            /**
             * 如果是系统异常，则增加异常数量。
             */
            // This should not happen.
            node.increaseExceptionQps(count);
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().increaseExceptionQps(count);
            }

            if (resourceWrapper.getEntryType() == EntryType.IN) {
                Constants.ENTRY_NODE.increaseExceptionQps(count);
            }
            throw e;
        }
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        DefaultNode node = (DefaultNode)context.getCurNode();

        if (context.getCurEntry().getError() == null) {
            /**
             * 成功执行，则重点关注响应时间和当前线程数，其实现如下：
             * 计算本次响应时间，将本次响应时间收集到 Node 中。
             * 将当前活跃线程数减一
             */
            // Calculate response time (max RT is statisticMaxRt from SentinelConfig).
            //计算响应时间
            long rt = TimeUtil.currentTimeMillis() - context.getCurEntry().getCreateTime();
            int maxStatisticRt = SentinelConfig.statisticMaxRt();
            if (rt > maxStatisticRt) {
                rt = maxStatisticRt;
            }

            // Record response time and success count.
            /**
             * 添加响应时间和成功指标
             */
            node.addRtAndSuccess(rt, count);
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().addRtAndSuccess(rt, count);
            }

            node.decreaseThreadNum();

            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().decreaseThreadNum();
            }

            if (resourceWrapper.getEntryType() == EntryType.IN) {
                Constants.ENTRY_NODE.addRtAndSuccess(rt, count);
                Constants.ENTRY_NODE.decreaseThreadNum();
            }
        } else {
            // Error may happen.
        }

        // Handle exit event with registered exit callback handlers.
        //执行退出时的 callback。可以通过 StatisticSlotCallbackRegistry 的 addExitCallback 方法添加退出回调函数。
        Collection<ProcessorSlotExitCallback> exitCallbacks = StatisticSlotCallbackRegistry.getExitCallbacks();
        for (ProcessorSlotExitCallback handler : exitCallbacks) {
            handler.onExit(context, resourceWrapper, count, args);
        }

        //传播exit事件
        fireExit(context, resourceWrapper, count);
    }
}
