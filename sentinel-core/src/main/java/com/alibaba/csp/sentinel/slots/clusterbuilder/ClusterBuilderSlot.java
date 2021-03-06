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
package com.alibaba.csp.sentinel.slots.clusterbuilder;

import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.node.*;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotChain;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slotchain.StringResourceWrapper;
import com.alibaba.csp.sentinel.spi.SpiOrder;

import java.util.HashMap;
import java.util.Map;

/**
 * <p>
 * This slot maintains resource running statistics (response time, qps, thread
 * count, exception), and a list of callers as well which is marked by
 * {@link ContextUtil#enter(String origin)}
 * </p>
 * <p>
 * One resource has only one cluster node, while one resource can have multiple
 * default nodes.
 * </p>
 *
 * @author jialiang.linjl
 *
 *
 *  用于存储资源的统计信息以及调用者信息，例如该资源的 RT, QPS, thread count 等等，这些信息将用作为多维度限流，降级的依据
 *  如果当前资源未创建 ClusterNode，则为资源创建 ClusterNode；将 ClusterNode 赋值给当前资源的 DefaultNode.clusterNode；
 *  如果调用来源（origin）不为空，则为调用来源创建 StatisticNode，用于实现按调用来源统计资源的指标数据，
 *  ClusterNode 持有每个调用来源的 StatisticNode。
 */
@SpiOrder(-9000)
public class ClusterBuilderSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    /**
     * <p>
     * Remember that same resource({@link ResourceWrapper#equals(Object)}) will share
     * the same {@link ProcessorSlotChain} globally, no matter in witch context. So if
     * code goes into {@link #entry(Context, ResourceWrapper, DefaultNode, int, boolean, Object...)},
     * the resource name must be same but context name may not.
     * </p>
     * <p>
     * To get total statistics of the same resource in different context, same resource
     * shares the same {@link ClusterNode} globally. All {@link ClusterNode}s are cached
     * in this map.
     * </p>
     * <p>
     * The longer the application runs, the more stable this mapping will
     * become. so we don't concurrent map but a lock. as this lock only happens
     * at the very beginning while concurrent map will hold the lock all the time.
     * </p>
     *
     *  持有的全局集群节点缓存表，其键为 Entrance Node 所对应的资源ID，即 Context 中关联的节点信息。
     *  因为是static的 所以所有的ClusterBuilderSlot对象共享这一个map
     */
    private static volatile Map<ResourceWrapper, ClusterNode> clusterNodeMap = new HashMap<>();

    private static final Object lock = new Object();


    /**
     * 一个资源对应一个ClusterBuilderSlot 同理对应一个clusterNode
     *    ClusterBuilderSlot每个资源一个实例，这里保存当前资源对应ClusterNode
     */
    private volatile ClusterNode clusterNode = null;



    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count,
                      boolean prioritized, Object... args)
        throws Throwable {
        //一个资源对应一个clusterNode 对应多个DefaultNode  同一个资源的多个DefaultNode对应一个clusterNode
        if (clusterNode == null) {
            synchronized (lock) {
                if (clusterNode == null) {
                    // Create the cluster node.
                    //创建ClusterNode
                    clusterNode = new ClusterNode(resourceWrapper.getName(), resourceWrapper.getResourceType());
                    HashMap<ResourceWrapper, ClusterNode> newMap = new HashMap<>(Math.max(clusterNodeMap.size(), 16));
                    newMap.putAll(clusterNodeMap);
                    newMap.put(node.getId(), clusterNode);

                    clusterNodeMap = newMap;
                }
            }
        }
        // 2. 保存ClusterNode到当前上下文正在处理的DefaultNode
        node.setClusterNode(clusterNode);

        /**
         * if context origin is set, we should get or create a new {@link Node} of
         * the specific origin.
         *
         * ContextUtil.enter(java.lang.String, java.lang.String)时设置的
         */
        if (!"".equals(context.getOrigin())) {
            /**
             * originNode ： 所谓的 orginNode，即在调用 ContextUtil 中 enter(String name, String origin) 方法中的第二个参数，
             * 表示这条调用链的源头，
             * 在 Dubbo 中默认为 应用的 application
             */
            //在这里根据来源创建StatisticNode并关联
            Node originNode = node.getClusterNode().getOrCreateOriginNode(context.getOrigin());
            //设置当前entry的调用方
            context.getCurEntry().setOriginNode(originNode);
        }

        fireEntry(context, resourceWrapper, node, count, prioritized, args);
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        fireExit(context, resourceWrapper, count, args);
    }

    /**
     * Get {@link ClusterNode} of the resource of the specific type.
     *
     * @param id   resource name.
     * @param type invoke type.
     * @return the {@link ClusterNode}
     */
    public static ClusterNode getClusterNode(String id, EntryType type) {
        return clusterNodeMap.get(new StringResourceWrapper(id, type));
    }

    /**
     * Get {@link ClusterNode} of the resource name.
     *
     * @param id resource name.
     * @return the {@link ClusterNode}.
     */
    public static ClusterNode getClusterNode(String id) {
        if (id == null) {
            return null;
        }
        ClusterNode clusterNode = null;

        for (EntryType nodeType : EntryType.values()) {
            //从全局缓存中获取该资源对应的集群节点
            clusterNode = clusterNodeMap.get(new StringResourceWrapper(id, nodeType));
            if (clusterNode != null) {
                break;
            }
        }

        return clusterNode;
    }

    /**
     * Get {@link ClusterNode}s map, this map holds all {@link ClusterNode}s, it's key is resource name,
     * value is the related {@link ClusterNode}. <br/>
     * DO NOT MODIFY the map returned.
     *
     * @return all {@link ClusterNode}s
     */
    public static Map<ResourceWrapper, ClusterNode> getClusterNodeMap() {
        return clusterNodeMap;
    }

    /**
     * Reset all {@link ClusterNode}s. Reset is needed when {@link IntervalProperty#INTERVAL} or
     * {@link SampleCountProperty#SAMPLE_COUNT} is changed.
     */
    public static void resetClusterNodes() {
        for (ClusterNode node : clusterNodeMap.values()) {
            node.reset();
        }
    }
}
