/*
 * Copyright 1999-2019 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel;

/**
 * @author Eric Zhao
 * @since 1.7.0
 */
public final class ResourceTypeConstants {
    // 默认普通资源
    public static final int COMMON = 0;
    //http
    public static final int COMMON_WEB = 1;
    //// RPC：Dubbo、Motan、Sofa-RPC
    public static final int COMMON_RPC = 2;
    // spring-cloud-gateway、zuul
    public static final int COMMON_API_GATEWAY = 3;
    //sql
    public static final int COMMON_DB_SQL = 4;

    private ResourceTypeConstants() {}
}
