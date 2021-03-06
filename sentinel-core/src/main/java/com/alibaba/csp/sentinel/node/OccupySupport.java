/*
 * Copyright 1999-2019 Alibaba Group Holding Ltd.
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

/**
 * @author Eric Zhao
 * @since 1.5.0
 *
 *   支持抢占未来的时间窗口，有点类似借用“未来”的令牌
 *
 */
public interface OccupySupport {

    /**
     * Try to occupy latter time windows' tokens. If occupy success, a value less than
     * {@code occupyTimeout} in {@link OccupyTimeoutProperty} will be return.
     *
     * <p>
     * Each time we occupy tokens of the future window, current thread should sleep for the
     * corresponding time for smoothing QPS. We can't occupy tokens of the future with unlimited,
     * the sleep time limit is {@code occupyTimeout} in {@link OccupyTimeoutProperty}.
     * </p>
     *
     * @param currentTime  current time millis.  当前时间
     * @param acquireCount tokens count to acquire.本次需要申请的令牌个数
     * @param threshold    qps threshold. 设置的阈值
     * @return time should sleep. Time >= {@code occupyTimeout} in {@link OccupyTimeoutProperty} means
     * occupy fail, in this case, the request should be rejected immediately.
     *
     *  尝试抢占未来的令牌，返回值为调用该方法的线程应该 sleep 的时间
     *
     */
    long tryOccupyNext(long currentTime, int acquireCount, double threshold);

    /**
     * Get current waiting amount. Useful for debug.
     *
     * @return current waiting amount
     *
     *  获取当前已申请的未来的令牌个数
     */
    long waiting();

    /**
     * Add request that occupied.
     *
     * @param futureTime   future timestamp that the acquireCount should be added on.
     * @param acquireCount tokens count.
     *
     *       申请未来事件窗口中的令牌
     */
    void addWaitingRequest(long futureTime, int acquireCount);

    /**
     * Add occupied pass request, which represents pass requests that borrow the latter windows' token.
     *
     * @param acquireCount tokens count.
     *
     *        增加申请未来令牌通过的个数
     */
    void addOccupiedPass(int acquireCount);

    /**
     * Get current occupied pass QPS.
     *
     * @return current occupied pass QPS
     *
     *  当前抢占未来令牌的qps
     */
    double occupiedPassQps();
}
