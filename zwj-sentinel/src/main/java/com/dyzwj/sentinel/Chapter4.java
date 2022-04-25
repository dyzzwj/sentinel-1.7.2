package com.dyzwj.sentinel;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slots.block.BlockException;

public class Chapter4{


    public static void main(String[] args) throws BlockException {

        ContextUtil.enter("entrance1", "appA");
        Entry nodeA = SphU.entry("nodeA");
        if (nodeA != null) {
            nodeA.exit();
        }
        ContextUtil.exit();
    }


}
