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
package com.alibaba.csp.sentinel.cluster.flow.statistic.data;

/**
 * @author Eric Zhao
 * @since 1.4.0
 *
 *  实现集群限流需要收集的指标数据有以下几种：
     * PASS：已经发放的令牌总数
     * BLOCK：令牌申请被驳回的总数
     * PASS_REQUEST：被放行的请求总数
     * BLOCK_REQUEST：被拒绝的请求总数
     * OCCUPIED_PASS：预占用，已经发放的令牌总数
     * OCCUPIED_BLOCK：预占用，令牌申请被驳回的总数
     * WAITING：当前等待下一个时间窗口到来的请求总数
 *
 */
public enum ClusterFlowEvent {

    /**
     * Normal pass.
     */
    PASS,
    /**
     * Normal block.
     */
    BLOCK,
    /**
     * Token request (from client) passed.
     */
    PASS_REQUEST,
    /**
     * Token request (from client) blocked.
     */
    BLOCK_REQUEST,
    /**
     * Pass (pre-occupy incoming buckets).
     */
    OCCUPIED_PASS,
    /**
     * Block (pre-occupy incoming buckets failed).
     */
    OCCUPIED_BLOCK,
    /**
     * Waiting due to flow shaping or for next bucket tick.
     */
    WAITING
}
