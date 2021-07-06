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
import com.alibaba.csp.sentinel.util.TimeUtil;

import java.util.concurrent.atomic.AtomicLong;

/**
 * @author jialiang.linjl
 * @since 1.4.0
 *
 *  Warmup + 匀速排队
 *
 */
public class WarmUpRateLimiterController extends WarmUpController {

    private final int timeoutInMs;
    private final AtomicLong latestPassedTime = new AtomicLong(-1);

    public WarmUpRateLimiterController(double count, int warmUpPeriodSec, int timeOutMs, int coldFactor) {
        super(count, warmUpPeriodSec, coldFactor);
        this.timeoutInMs = timeOutMs;
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }

    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        //获取当前滑动窗口的前一个窗口收集的通过QPS
        long previousQps = (long) node.previousPassQps();
        //更新 storedTokens 与 lastFilledTime 的值，即按照令牌发放速率发送指定令牌
        syncToken(previousQps);

        long currentTime = TimeUtil.currentTimeMillis();
        //当前已发放的许可
        long restToken = storedTokens.get();
        long costTime = 0;
        long expectedTime = 0;
        //已发放的许可 大于 warningToken 进入梯形区域

        /**
         * 与RateLimiter的区别就是计算获取一个许可的时间是warmup的
         */
        if (restToken >= warningToken) {
            long aboveToken = restToken - warningToken;

            // current interval = restToken*slope+1/count
            // aboveToken * slope + 1.0 / count ：当前获取一个perimit需要的时间
            //1.0 / (aboveToken * slope + 1.0 / count) : 当前的速率
            //waringTokens随着的时间的推移 越来与小(速率越来越快) 当stroedToken <= warningToken 趋于平稳
            double warmingQps = Math.nextUp(1.0 / (aboveToken * slope + 1.0 / count));
            //获取acquireCount个许可需要的时间
            costTime = Math.round(1.0 * (acquireCount) / warmingQps * 1000);
        } else {
            //获取acquireCount个许可需要的时间
            //走到这里 没有梯形区域 获取一个许可的时间是稳定的
            costTime = Math.round(1.0 * (acquireCount) / count * 1000);
        }
        //计算成功获取acquireCount个数量的令牌的时间戳
        expectedTime = costTime + latestPassedTime.get();

        //如果能成功获取acquireCount个数量的令牌的时间戳 小于当前时间 （现在就可以直接获得acquireCount个数量的令牌）
        if (expectedTime <= currentTime) {
            latestPassedTime.set(currentTime);
            return true;
        } else {
            long waitTime = costTime + latestPassedTime.get() - currentTime;
            if (waitTime > timeoutInMs) {
                return false;
            } else {
                long oldTime = latestPassedTime.addAndGet(costTime);
                try {
                    waitTime = oldTime - TimeUtil.currentTimeMillis();
                    if (waitTime > timeoutInMs) {
                        latestPassedTime.addAndGet(-costTime);
                        return false;
                    }
                    if (waitTime > 0) {
                        Thread.sleep(waitTime);
                    }
                    return true;
                } catch (InterruptedException e) {
                }
            }
        }
        return false;
    }
}
