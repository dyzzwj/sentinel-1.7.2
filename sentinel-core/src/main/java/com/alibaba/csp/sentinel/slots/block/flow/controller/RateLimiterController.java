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
package com.alibaba.csp.sentinel.slots.block.flow.controller;

import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slots.block.flow.TrafficShapingController;
import com.alibaba.csp.sentinel.util.TimeUtil;

import java.util.concurrent.atomic.AtomicLong;

/**
 * @author jialiang.linjl
 *  匀速排队策略
 *
 */
public class RateLimiterController implements TrafficShapingController {

    /**
     * 排队等待的最大超时时间，如果等待超过该时间，将会抛出 FlowException。
     */
    private final int maxQueueingTimeMs;
    /**
     * 流控规则中的阔值，即令牌的总个数，以QPS为例，如果该值设置为1000，则表示1s可并发的请求数量。
     */
    private final double count;

    /**
     *  上一次成功通过的时间戳。
     */
    private final AtomicLong latestPassedTime = new AtomicLong(-1);

    public RateLimiterController(int timeOut, double count) {
        this.maxQueueingTimeMs = timeOut;
        this.count = count;
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }


    /**
     * 主要是记录上一次请求通过的时间戳，然后根据流控规则，判断两次请求之间最小的间隔，并加入一个排队时间。
     * @return
     */
    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        // Pass when acquire count is less or equal than 0.
        if (acquireCount <= 0) {
            return true;
        }
        // Reject when count is less or equal than 0.
        // Otherwise,the costTime will be max of long and waitTime will overflow in some cases.

        if (count <= 0) {
            return false;
        }

        long currentTime = TimeUtil.currentTimeMillis();
        // Calculate the interval between every two requests.

        /**
         * 计算获取acquireCount个令牌需要的时间
         * 首先算出每一个请求之间最小的间隔，时间单位为毫秒。
         * 例如 cout 设置为 1000,表示一秒可以通过 1000个请求，匀速排队，那每个请求的间隔为 1 / 1000(s)，
         * 乘以1000将时间单位转换为毫秒，如果一次需要2个令牌，则其间隔时间为2ms，用 costTime 表示
         */
        long costTime = Math.round(1.0 * (acquireCount) / count * 1000);

        // Expected pass time of this request.

        /**
         *  计算成功获取acquireCount个数量的令牌的时间戳
         * 计算下一个请求（本次）的期望达到时间，等于上一次通过的时间戳 + costTime ，用 expectedTime 表示。
         */
        long expectedTime = costTime + latestPassedTime.get();

        //如果能成功获取acquireCount个数量的令牌的时间戳 小于当前时间 （现在就可以直接获得acquireCount个数量的令牌）
        if (expectedTime <= currentTime) {
            // Contention may exist here, but it's okay.
            //更新上一次成功通过的时间戳
            latestPassedTime.set(currentTime);
            //通过
            return true;
        } else {
            //如果能成功获取acquireCount个数量的令牌的时间戳 大于 当前时间 （现在还无法直接获得acquireCount个数量的令牌，需要等待）
            // Calculate the time to wait.
            //计算获取acquireCount个数量的令牌需要等待的时间
            long waitTime = costTime + latestPassedTime.get() - TimeUtil.currentTimeMillis();
            //如果需要等待的时间大于 排队等待的最大超时时间 返回false 即未通过
            if (waitTime > maxQueueingTimeMs) {
                return false;
            } else {
                //如果需要等待的时间 小于 排队等待的最大超时时间
                //先计算上一次成功通过的时间戳
                long oldTime = latestPassedTime.addAndGet(costTime);
                try {
                    //再次计算需要等待的时间
                    waitTime = oldTime - TimeUtil.currentTimeMillis();
                    //如果需要等待的时间大于 排队等待的最大超时时间 返回false 即未通过
                    if (waitTime > maxQueueingTimeMs) {
                        latestPassedTime.addAndGet(-costTime);
                        return false;
                    }
                    // in race condition waitTime may <= 0
                    //等待
                    if (waitTime > 0) {
                        Thread.sleep(waitTime);
                    }
                    //通过
                    return true;
                } catch (InterruptedException e) {
                }
            }
        }
        return false;
    }

}
