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
package com.alibaba.csp.sentinel.cluster;

import java.util.Map;

/**
 * Result entity of acquiring cluster flow token.
 *
 * @author Eric Zhao
 * @since 1.4.0
 */
public class TokenResult {

    /**
     *  请求的响应状态那
     */
    private Integer status;

    /**
     *  当前时间窗口剩余的令牌数
     */
    private int remaining;
    /**
     *  休眠等待时间，单位毫秒，用于告诉客户端，当前请求可以放行，但需要先休眠指定时间后才能放行。
     */
    private int waitInMs;

    /**
     * 附带的属性，暂未使用。
     */
    private Map<String, String> attachments;

    public TokenResult() {}

    public TokenResult(Integer status) {
        this.status = status;
    }

    public Integer getStatus() {
        return status;
    }

    public TokenResult setStatus(Integer status) {
        this.status = status;
        return this;
    }

    public int getRemaining() {
        return remaining;
    }

    public TokenResult setRemaining(int remaining) {
        this.remaining = remaining;
        return this;
    }

    public int getWaitInMs() {
        return waitInMs;
    }

    public TokenResult setWaitInMs(int waitInMs) {
        this.waitInMs = waitInMs;
        return this;
    }

    public Map<String, String> getAttachments() {
        return attachments;
    }

    public TokenResult setAttachments(Map<String, String> attachments) {
        this.attachments = attachments;
        return this;
    }

    @Override
    public String toString() {
        return "TokenResult{" +
            "status=" + status +
            ", remaining=" + remaining +
            ", waitInMs=" + waitInMs +
            ", attachments=" + attachments +
            '}';
    }
}
