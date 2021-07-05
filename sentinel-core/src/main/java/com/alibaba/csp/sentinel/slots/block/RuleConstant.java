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
package com.alibaba.csp.sentinel.slots.block;

import com.alibaba.csp.sentinel.node.IntervalProperty;

/**
 * @author youji.zj
 * @author jialiang.linjl
 */
public final class RuleConstant {

    //线程数
    public static final int FLOW_GRADE_THREAD = 0;
    //qps
    public static final int FLOW_GRADE_QPS = 1;

    public static final int DEGRADE_GRADE_RT = 0;
    /**
     * Degrade by biz exception ratio in the current {@link IntervalProperty#INTERVAL} second(s).
     */
    public static final int DEGRADE_GRADE_EXCEPTION_RATIO = 1;
    /**
     * Degrade by biz exception count in the last 60 seconds.
     */
    public static final int DEGRADE_GRADE_EXCEPTION_COUNT = 2;

    public static final int DEGRADE_DEFAULT_SLOW_REQUEST_AMOUNT = 5;
    public static final int DEGRADE_DEFAULT_MIN_REQUEST_AMOUNT = 5;

    public static final int AUTHORITY_WHITE = 0;
    public static final int AUTHORITY_BLACK = 1;


    /**
     * 直接： 根据调用方限流
     * ContextUtil.enter(resourceName, origin) 方法中的 origin 参数标明了调用方身份。这些信息会在 StatisticSlot 中被统计
     * 限流规则中的 limitApp 字段用于根据调用方进行流量控制。该字段的值有以下三种选项，分别对应不同的场景：
     *
     * default：表示不区分调用者，来自任何调用者的请求都将进行限流统计。如果这个资源名的调用总和超过了这条规则定义的阈值，则触发限流。
     * {some_origin_name}：表示针对特定的调用者，只有来自这个调用者的请求才会进行流量控制。例如 NodeA 配置了一条针对调用者caller1的规则，那么当且仅当来自 caller1 对 NodeA 的请求才会触发流量控制。
     * other：表示针对除 {some_origin_name} 以外的其余调用方的流量进行流量控制。例如，资源NodeA配置了一条针对调用者 caller1 的限流规则，同时又配置了一条调用者为 other 的规则，那么任意来自非 caller1 对 NodeA 的调用，都不能超过 other 这条规则定义的阈值。
     * 同一个资源名可以配置多条规则，规则的生效顺序为：{some_origin_name} > other > default
     */
    public static final int STRATEGY_DIRECT = 0;

    /**
     * 关联：当关联的资源达到阈值时，就限流自己 关联模式的核心就是保护关联资源的
     * 当两个资源之间具有资源争抢或者依赖关系的时候，这两个资源便具有了关联。比如对数据库同一个字段的读操作和写操作存在争抢，
     * 读的速度过高会影响写得速度，写的速度过高会影响读的速度。如果放任读写操作争抢资源，则争抢本身带来的开销会降低整体的吞吐量。
     *
     * 可使用关联限流来避免具有关联关系的资源之间过度的争抢，举例来说，read_db 和 write_db 这两个资源分别代表数据库读写，
     * 我们可以给 read_db 设置限流规则来达到写优先的目的：设置 FlowRule.strategy 为 RuleConstant.STRATEGY_RELATE
     * 同时设置 FlowRule.refResource 为 write_db。这样当写库操作过于频繁时，读数据的请求会被限流。
     */
    public static final int STRATEGY_RELATE = 1;
    /**
     *
     * 链路：根据调用链路入口限流 只记录指定链路上的流量 (指定资源从入口资源进来的流量，如果达到阈值，就进行限流)【api级别的针对来源】
     *  当从某个接口过来的资源达到限流条件时，就开启限流
     *  比如我们可以设置 FlowRule.strategy 为 RuleConstant.STRATEGY_CHAIN，同时设置 FlowRule.refResource 为 Entrance1 来表示只有从入口 Entrance1 的调用才会记录到 NodeA 的限流统计当中，而对来自 Entrance2 的调用漠不关心。
     *
     * 调用链的入口是通过 API 方法 ContextUtil.enter(name) 定义的
     *  链路的功能有点类似于针对来源配置项，区别在于：针对来源是针对上级微服务，而链路流控是针对本服务入口，也就是说它的粒度更细。
     */
    public static final int STRATEGY_CHAIN = 2;

    public static final int CONTROL_BEHAVIOR_DEFAULT = 0;
    public static final int CONTROL_BEHAVIOR_WARM_UP = 1;
    public static final int CONTROL_BEHAVIOR_RATE_LIMITER = 2;
    public static final int CONTROL_BEHAVIOR_WARM_UP_RATE_LIMITER = 3;

    public static final String LIMIT_APP_DEFAULT = "default";
    public static final String LIMIT_APP_OTHER = "other";

    public static final int DEFAULT_SAMPLE_COUNT = 2;
    public static final int DEFAULT_WINDOW_INTERVAL_MS = 1000;

    private RuleConstant() {}
}
