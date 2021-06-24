package com.dyzwj.sentinel;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;

import java.util.ArrayList;
import java.util.List;

public class Chapter3 {

    public static void main(String[] args) {
        //配置规则
        try {
            Context context= ContextUtil.enter("context1");
            Entry entry= SphU.entry("A");
            Entry entry2=SphU.entry("B");

            entry.exit();
            entry2.exit();
            Entry entry3=SphU.entry("A");
            Entry entry4=SphU.entry("B");
            entry3.exit();
            entry4.exit();



            ContextUtil.exit();
        } catch (BlockException ex) {
            // 处理被流控的逻辑
            System.out.println("blocked!");
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private static void initFlowRules(){
        List<FlowRule> rules = new ArrayList<>();
        FlowRule rule = new FlowRule();
        rule.setResource("HelloWorld");
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        // Set limit QPS to 20.
        rule.setCount(20);
        rules.add(rule);
        FlowRuleManager.loadRules(rules);
    }

}
