package domi.argenticpptmaster.agent;

import domi.argenticpptmaster.domain.PptJob;

/**
 * AgentScope 工作流代理工厂接口。
 * <p>
 * 定义创建 {@link AgentScopeWorkflowAgent} 实例的工厂契约。
 * 实现类根据 {@link PptJob} 任务信息，构建并配置 AgentScope 工作流代理实例，
 * 包括模型配置、提示词构建、工具注册等初始化工作。
 * </p>
 */
public interface AgentScopeWorkflowAgentFactory {

    /**
     * 根据 PPT 任务信息创建 AgentScope 工作流代理实例。
     * <p>
     * 基于给定的 {@link PptJob} 初始化并返回一个配置完成的
     * {@link AgentScopeWorkflowAgent} 实例。创建过程包括加载模型配置、
     * 构建系统提示词、注册工具函数以及设置工作流运行时上下文。
     * </p>
     *
     * @param job PPT 任务信息，用于配置代理的行为参数
     * @return 配置完成的 AgentScope 工作流代理实例
     */
    AgentScopeWorkflowAgent create(PptJob job);
}
