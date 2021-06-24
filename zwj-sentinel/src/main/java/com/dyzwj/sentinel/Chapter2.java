package com.dyzwj.sentinel;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slots.block.BlockException;

public class Chapter2 {
    public static void main(String[] args) {
        try {
            Context context= ContextUtil.enter("context1");
            Entry entry= SphU.entry("A");
            Entry entry2=SphU.entry("B");
            Entry entry3=SphU.entry("C");
            Entry entry4=SphU.entry("D");
            Entry entry5=SphU.entry("E");
            entry.exit();
            entry2.exit();
            entry3.exit();
            entry4.exit();
            entry5.exit();


            ContextUtil.exit();
        } catch (BlockException ex) {
            // 处理被流控的逻辑
            System.out.println("blocked!");
        }catch (Exception e){
            e.printStackTrace();
        }
    }

}
