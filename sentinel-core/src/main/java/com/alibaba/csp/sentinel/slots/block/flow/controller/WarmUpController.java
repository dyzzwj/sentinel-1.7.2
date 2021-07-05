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
 * <p>
 * The principle idea comes from Guava. However, the calculation of Guava is
 * rate-based, which means that we need to translate rate to QPS.
 * </p>
 *
 * <p>
 * Requests arriving at the pulse may drag down long idle systems even though it
 * has a much larger handling capability in stable period. It usually happens in
 * scenarios that require extra time for initialization, e.g. DB establishes a connection,
 * connects to a remote service, and so on. That’s why we need “warm up”.
 * </p>
 *
 * <p>
 * Sentinel's "warm-up" implementation is based on the Guava's algorithm.
 * However, Guava’s implementation focuses on adjusting the request interval,
 * which is similar to leaky bucket. Sentinel pays more attention to
 * controlling the count of incoming requests per second without calculating its interval,
 * which resembles token bucket algorithm.
 * </p>
 *
 * <p>
 * The remaining tokens in the bucket is used to measure the system utility.
 * Suppose a system can handle b requests per second. Every second b tokens will
 * be added into the bucket until the bucket is full. And when system processes
 * a request, it takes a token from the bucket. The more tokens left in the
 * bucket, the lower the utilization of the system; when the token in the token
 * bucket is above a certain threshold, we call it in a "saturation" state.
 * </p>
 *
 * <p>
 * Base on Guava’s theory, there is a linear equation we can write this in the
 * form y = m * x + b where y (a.k.a y(x)), or qps(q)), is our expected QPS
 * given a saturated period (e.g. 3 minutes in), m is the rate of change from
 * our cold (minimum) rate to our stable (maximum) rate, x (or q) is the
 * occupied token.
 * </p>
 *
 * @author jialiang.linjl
 *    根据codeFactor（冷加载因子，默认为3）的值，即请求 QPS 从阈值 / codeFactor，经过预热时长，逐渐升至设定的QPS阈值；
 */
public class WarmUpController implements TrafficShapingController {

    /**
     *  double count
     * 流控规则设定的阔值。 stableIntervalMicros  count = 1 / stableIntervalMicros(获取一个令牌需要的时间)
     */
    protected double count;
    /**
     * 冷却因子
     */
    private int coldFactor;
    /**
     * 告警token，对应 Guava 中的 RateLimiter 中的 thresholdPermits
     */
    protected int warningToken = 0;
    /**
     * 最大允许缓存的 permits 数量，也就是 storedPermits 能达到的最大值
     */
    private int maxToken;

    /**
     * 斜率
     */
    protected double slope;

    /**
     *  当前可用的许可数量
     *   当前还有多少 permits 没有被使用，被存下来的 permits 数量
     */
    protected AtomicLong storedTokens = new AtomicLong(0);
    /**
     * 上次发放令牌的时间
     */
    protected AtomicLong lastFilledTime = new AtomicLong(0);

    public WarmUpController(double count, int warmUpPeriodInSec, int coldFactor) {
        construct(count, warmUpPeriodInSec, coldFactor);
    }

    public WarmUpController(double count, int warmUpPeriodInSec) {
        construct(count, warmUpPeriodInSec, 3);
    }

    private void construct(double count, int warmUpPeriodInSec, int coldFactor) {

        /**
         * count：限流规则配置的阔值，例如是按 TPS 类型来限流，如果限制为100tps，则该值为100。
         * int warmUpPeriodInSec：预热时间，单位为秒，通用在限流规则页面可配置。
         * int coldFactor：冷却因子，这里默认为3，与 RateLimiter 中的冷却因子保持一致，表示的含义为 coldIntervalMicros 与 stableIntervalMicros 的比值。
         */
        if (coldFactor <= 1) {
            throw new IllegalArgumentException("Cold factor should be larger than 1");
        }

        this.count = count;

        this.coldFactor = coldFactor;

        /**
         *   warningToken ==>  thresholdPermits
         *   stableInteral = 1 / count
         *   计算 warningToken 的值，与 Guava 中的 RateLimiter 中的 thresholdPermits 的计算算法公式相同，
         *   thresholdPermits = 0.5 * warmupPeriod / stableInterval，在Sentienl 中，
         *   而 stableInteral = 1 / count，thresholdPermits 表达式中的 0.5 就是因为 codeFactor 为3，
         *   因为 warm up period与 stable 面积之比等于 (coldIntervalMicros - stableIntervalMicros ) 与 stableIntervalMicros 的比值，
         *   这个比值又等于 coldIntervalMicros / stableIntervalMicros - stableIntervalMicros / stableIntervalMicros 等于 coldFactor - 1。
         */
        // thresholdPermits = 0.5 * warmupPeriod / stableInterval.
        // warningToken = 100;
        warningToken = (int)(warmUpPeriodInSec * count) / (coldFactor - 1);
        // / maxPermits = thresholdPermits + 2 * warmupPeriod /
        // (stableInterval + coldInterval)
        // maxToken = 200
        maxToken = warningToken + (int)(2 * warmUpPeriodInSec * count / (1.0 + coldFactor));

        // slope = (coldIntervalMicros - stableIntervalMicros) / (maxPermits - thresholdPermits);
        slope = (coldFactor - 1.0) / count / (maxToken - warningToken);

    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }

    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        //当前节点已通过的qps（1分钟内每秒平均的通过的qps）== 当前已发放的令牌
        long passQps = (long) node.passQps();
        //获取当前滑动窗口的前一个窗口收集的已通过QPS
        long previousQps = (long) node.previousPassQps();
        //更新 storedTokens 与 lastFilledTime 的值，即按照令牌发放速率发送指定令牌
        syncToken(previousQps);

        //当前存储的许可
        long restToken = storedTokens.get();
        //如果当前存储的许可大于warningToken的处理逻辑，主要是在预热阶段允许通过的速率会比限流规则设定的速率要低，
        // 判断是否通过的依据就是当前通过的TPS与申请的许可数是否小于当前的速率（这个值加入斜率，即在预热期间，速率是慢慢达到设定速率的。）
        if (restToken >= warningToken) {//右边梯形部分有令牌
            // 如果进入了警戒线，开始调整他的qps
            //计算右边梯形部分的令牌数
            long aboveToken = restToken - warningToken;
            // current interval = restToken*slope+1/count
            // aboveToken * slope + 1.0 / count ：获取一个perimit需要的时间
            //1.0 / (aboveToken * slope + 1.0 / count) : 当前的速率
            double warningQps = Math.nextUp(1.0 / (aboveToken * slope + 1.0 / count));
            //当前节点已通过的qps（已发放的令牌） + 申请的令牌数 <= 当前的速率
            if (passQps + acquireCount <= warningQps) {
                return true;
            }
        } else {//当前存储的许可小于warningToken，则按照规则设定的速率进行判定。
            //获取小于warningToken的许可的时间是恒定的
            if (passQps + acquireCount <= count) {
                return true;
            }
        }

        return false;
    }

    protected void syncToken(long passQps) {
        long currentTime = TimeUtil.currentTimeMillis();
        //这个是计算出当前时间秒的最开始时间。例如当前是 2020-04-06 08:29:01:056，该方法返回的时间为 2020-04-06 08:29:01:000。
        currentTime = currentTime - currentTime % 1000;

        long oldLastFillTime = lastFilledTime.get();
        /**
         * 如果当前时间 小于 上次发放许可的时间 则跳过，无法发放令牌，即每秒发放一次令牌。
         *  由于上次发放令牌的时间 以 秒 来记录 所以可以理解为每秒发放一次令牌
         *
         */

        if (currentTime <= oldLastFillTime) {
            return;
        }
        //当前存储的令牌数
        long oldValue = storedTokens.get();
        //返回最新的 存储的令牌
        long newValue = coolDownTokens(currentTime, passQps);
        //更新存储的令牌数
        if (storedTokens.compareAndSet(oldValue, newValue)) {
            //因为每秒发放一次令牌，所以生成令牌后要减去上一个滑动窗口通过的令牌
            long currentValue = storedTokens.addAndGet(0 - passQps);
            if (currentValue < 0) {
                storedTokens.set(0L);
            }
            //更新上一次发送许可的时间
            lastFilledTime.set(currentTime);
        }

    }

    private long coolDownTokens(long currentTime, long passQps) {
        //当前存储的令牌数
        long oldValue = storedTokens.get();
        long newValue = oldValue;

        // 添加令牌的判断前提条件:
        // 当令牌的消耗程度远远低于警戒线的时候
        if (oldValue < warningToken) {
            //右边梯形没有令牌了
            //能走到这里 currentTime 一定大于 lastFilledTime.get()
            //如果当前时间 大于 上次发放许可的时间，则需要重新计算许可，即又可以向许可池中添加许可（补 上次发放许可的时间到当前时间的令牌）
            newValue = (long)(oldValue + (currentTime - lastFilledTime.get()) * count / 1000);
        } else if (oldValue > warningToken) {
            //右边梯形还有令牌

            //如果当前剩余的 token 大于警戒线 但 前一秒的QPS小于 (count 与 冷却因子的比)，也发放许可（这里我不是太明白其用意）
            if (passQps < (int)count / coldFactor) {
                newValue = (long)(oldValue + (currentTime - lastFilledTime.get()) * count / 1000);
            }
        }
        /**
         * 这里是关键点，第一次运行，由于 lastFilledTime 等于0，这里将返回的是 maxToken，
         * 故这里一开始的许可就会超过 warningToken，启动预热机制，进行速率限制。
         * 从而一开始进入到预热阶段，此时的速率有一个爬坡的过程，类似于数学中的斜率，达到其他启动预热的效果。
         */
        return Math.min(newValue, maxToken);
    }

}
