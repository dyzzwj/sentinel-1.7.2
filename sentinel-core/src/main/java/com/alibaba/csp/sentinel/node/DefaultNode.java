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

import com.alibaba.csp.sentinel.SphO;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.nodeselector.NodeSelectorSlot;

import java.util.HashSet;
import java.util.Set;

/**
 * <p>
 * A {@link Node} used to hold statistics for specific resource name in the specific context.
 * Each distinct resource in each distinct {@link Context} will corresponding to a {@link DefaultNode}.
 * </p>
 * <p>
 * This class may have a list of sub {@link DefaultNode}s. Child nodes will be created when
 * calling {@link SphU}#entry() or {@link SphO}@entry() multiple times in the same {@link Context}.
 * </p>
 *
 * @author qinan.qn
 * @see NodeSelectorSlot
 *
 *      统计维度是Context + Resource，表示同一个上下文中的同一资源，共享一个DefaultNode实例，在NodeSelectorSlot中创建；。
 *  用于在特定上下文环境中保存某一个资源的实时统计信息。
 *  保存着某个resource在某个context中的实时指标，同一个resource的每个DefaultNode都指向一个ClusterNode
 *
 *  表示该 Node 用于统计哪个资源的实时指标数据，指标数据统计则由父类 StatisticNode 完成。
 *  DefaultNode重写了StaticNode更新统计数据的相关方法，如increaseBlockQps增加被拒绝的QPS时，额外调用了ClusterNode的increaseBlockQps方法。
 *
 */
public class DefaultNode extends StatisticNode {

    /**
     * The resource associated with the node.
     *
     * 资源id，即 DefaultNode 才真正与资源挂钩，可以将 DefaultNode 看出是调用链中的一个节点，并且与资源关联。
     */
    private ResourceWrapper id;

    /**
     * The list of all child nodes.
     *
     * 子节点结合。以此来维持其调用链 通过这种方式，DefaultNode构成了一颗树。
     */
    private volatile Set<Node> childList = new HashSet<>();

    /**
     * Associated cluster node.
     *
     *  关联的ClusterNode集群节点
     * 一个资源对应一个clusterNode 对应多个DefaultNode  同一个资源的多个DefaultNode对应一个clusterNode
     *
     */
    private ClusterNode clusterNode;

    public DefaultNode(ResourceWrapper id, ClusterNode clusterNode) {
        this.id = id;
        this.clusterNode = clusterNode;
    }

    public ResourceWrapper getId() {
        return id;
    }

    public ClusterNode getClusterNode() {
        return clusterNode;
    }

    public void setClusterNode(ClusterNode clusterNode) {
        this.clusterNode = clusterNode;
    }

    /**
     * Add child node to current node.
     *
     * @param node valid child node
     */
    public void addChild(Node node) {
        if (node == null) {
            RecordLog.warn("Trying to add null child to node <{}>, ignored", id.getName());
            return;
        }
        //如果子节点包含了当前节点 则跳过
        if (!childList.contains(node)) {
            synchronized (this) {
                //不包含当前节点 则将当前节点挂在子节点里面
                if (!childList.contains(node)) {
                    Set<Node> newSet = new HashSet<>(childList.size() + 1);
                    newSet.addAll(childList);
                    newSet.add(node);
                    childList = newSet;
                }
            }
            RecordLog.info("Add child <{}> to node <{}>", ((DefaultNode)node).id.getName(), id.getName());
        }
    }

    /**
     * Reset the child node list.
     */
    public void removeChildList() {
        this.childList = new HashSet<>();
    }

    public Set<Node> getChildList() {
        return childList;
    }

    /**
     * DefaultNode 的此类方法，通常是先调用 StatisticNode 的方法，然后再调用 clusterNode 的相关方法，最终就是使用在对应的滑动窗口中增加或减少计量值。
     * @param count
     */
    @Override
    public void increaseBlockQps(int count) {
        super.increaseBlockQps(count);
        this.clusterNode.increaseBlockQps(count);
    }

    @Override
    public void increaseExceptionQps(int count) {
        super.increaseExceptionQps(count);
        this.clusterNode.increaseExceptionQps(count);
    }

    @Override
    public void addRtAndSuccess(long rt, int successCount) {
        super.addRtAndSuccess(rt, successCount);
        this.clusterNode.addRtAndSuccess(rt, successCount);
    }

    @Override
    public void increaseThreadNum() {
        super.increaseThreadNum();
        this.clusterNode.increaseThreadNum();
    }

    @Override
    public void decreaseThreadNum() {
        super.decreaseThreadNum();
        this.clusterNode.decreaseThreadNum();
    }

    @Override
    public void addPassRequest(int count) {
        super.addPassRequest(count);
        this.clusterNode.addPassRequest(count);
    }

    /**
     * 来打印该节点的调用链
     */
    public void printDefaultNode() {
        visitTree(0, this);
    }

    private void visitTree(int level, DefaultNode node) {
        for (int i = 0; i < level; ++i) {
            System.out.print("-");
        }
        if (!(node instanceof EntranceNode)) {
            System.out.println(
                String.format("%s(thread:%s pq:%s bq:%s tq:%s rt:%s 1mp:%s 1mb:%s 1mt:%s)", node.id.getShowName(),
                    node.curThreadNum(), node.passQps(), node.blockQps(), node.totalQps(), node.avgRt(),
                    node.totalRequest() - node.blockRequest(), node.blockRequest(), node.totalRequest()));
        } else {
            System.out.println(
                String.format("Entry-%s(t:%s pq:%s bq:%s tq:%s rt:%s 1mp:%s 1mb:%s 1mt:%s)", node.id.getShowName(),
                    node.curThreadNum(), node.passQps(), node.blockQps(), node.totalQps(), node.avgRt(),
                    node.totalRequest() - node.blockRequest(), node.blockRequest(), node.totalRequest()));
        }
        for (Node n : node.getChildList()) {
            DefaultNode dn = (DefaultNode)n;
            visitTree(level + 1, dn);
        }
    }

}
