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
package com.alibaba.csp.sentinel;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.context.NullContext;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;

/**
 * Linked entry within current context.
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 *
 *
 *   如果在处理一个请求的路径上多次调用 SphU#entry，那么这些 CtEntry 会构成一个双向链表。
 *   在每次创建 CtEntry，都会将 Context.curEntry 设置为这个新的 CtEntry，
 *   双向链表的作用就是在调用 CtEntry#exit 方法时，能够将 Context.curEntry 还原为上一个资源的 CtEntry。
 */
class CtEntry extends Entry {

    /**
     * entry的父entry，用于在同一个context上下文中，多次调用entry方法，保存entry之间的关系
     */
    protected Entry parent = null;
    /**
     * entry的子entry，与parent相反
     */
    protected Entry child = null;

    protected ProcessorSlot<Object> chain;
    protected Context context;

    CtEntry(ResourceWrapper resourceWrapper, ProcessorSlot<Object> chain, Context context) {
        super(resourceWrapper);
        this.chain = chain;
        this.context = context;

        setUpEntryFor(context);
    }

    private void setUpEntryFor(Context context) {
        // The entry should not be associated to NullContext.
        if (context instanceof NullContext) {
            return;
        }
        //当在一个上下文中多次调用了 SphU#entry() 方法时，就会创建一棵调用链树(双向链表chile、parent)
        //第一次调用SphU.entry()的时候 context.getCurEntry必定是null  后面exit时根据入口节点的parent为null说明该Entry已经是最顶层的根节点了，可以清除context。
        //每个调用链的入口Entry的parent为null
        this.parent = context.getCurEntry();
        if (parent != null) {
            //context的curEntry为当前Entry的父entry
            ((CtEntry)parent).child = this;
        }
        //设置context的当前entry
        context.setCurEntry(this);
    }

    @Override
    public void exit(int count, Object... args) throws ErrorEntryFreeException {
        trueExit(count, args);
    }

    protected void exitForContext(Context context, int count, Object... args) throws ErrorEntryFreeException {
        if (context != null) {
            // Null context should exit without clean-up.
            if (context instanceof NullContext) {
                return;
            }
            if (context.getCurEntry() != this) {
                String curEntryNameInContext = context.getCurEntry() == null ? null : context.getCurEntry().getResourceWrapper().getName();
                // Clean previous call stack.
                CtEntry e = (CtEntry)context.getCurEntry();
                while (e != null) {
                    e.exit(count, args);
                    e = (CtEntry)e.parent;
                }
                String errorMessage = String.format("The order of entry exit can't be paired with the order of entry"
                    + ", current entry in context: <%s>, but expected: <%s>", curEntryNameInContext, resourceWrapper.getName());
                throw new ErrorEntryFreeException(errorMessage);
            } else {
                if (chain != null) {
                    chain.exit(context, resourceWrapper, count, args);
                }
                // Restore the call stack.
                context.setCurEntry(parent);
                if (parent != null) {
                    ((CtEntry)parent).child = null;
                }
                if (parent == null) {
                    // Default context (auto entered) will be exited automatically.
                    if (ContextUtil.isDefaultContext(context)) {
                        ContextUtil.exit();
                    }
                }
                // Clean the reference of context in current entry to avoid duplicate exit.
                clearEntryContext();
            }
        }
    }

    protected void clearEntryContext() {
        this.context = null;
    }

    @Override
    protected Entry trueExit(int count, Object... args) throws ErrorEntryFreeException {
        exitForContext(context, count, args);

        return parent;
    }

    @Override
    public Node getLastNode() {
        return parent == null ? null : parent.getCurNode();
    }
}