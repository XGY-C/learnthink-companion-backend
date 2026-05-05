package com.learnthink.core.agent.base;

/**
 * Agent基础接口
 */
public interface Agent {
    /**
     * 执行Agent任务
     * @param input 输入参数
     * @return 执行结果
     */
    String execute(String input);
    
    /**
     * 获取Agent名称
     * @return Agent名称
     */
    String getName();
}
