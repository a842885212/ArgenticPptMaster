package domi.argenticpptmaster.agent;

import domi.argenticpptmaster.domain.PptConfirmation;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;

/**
 * AI 代理运行器接口。
 * <p>
 * 定义 PPT 生成工作流中 AI 代理的启动和恢复执行方法。
 * 实现类负责协调 AI 代理的生命周期管理，包括工作流的初始启动
 * 以及从中断点恢复执行的能力。
 * </p>
 */
public interface PptAgentRunner {

    /**
     * 启动 PPT 生成工作流。
     * <p>
     * 根据提供的 PPT 任务信息初始化并启动 AI 代理工作流。
     * </p>
     *
     * @param job PPT 任务信息，包含任务标识、源文件路径、配置参数等
     */
    void start(PptJob job);

    /**
     * 从中断点恢复 PPT 工作流的执行。
     * <p>
     * 当工作流因等待用户确认或其他原因暂停后，调用此方法恢复执行。
     * </p>
     *
     * @param job          PPT 任务信息，包含恢复所需的上下文状态
     * @param confirmation 用户的确认结果，包含审批意见和可能的修改反馈
     */
    void resume(PptJob job, PptConfirmation confirmation);

    /**
     * 从指定 checkpoint 节点继续执行失败后的 PPT 工作流。
     * <p>
     * 与 {@link #resume(PptJob, PptConfirmation)} 不同，此方法用于任务异常失败后的恢复。
     * 它会启动一次新的 Agent attempt session，并明确告知 Agent 已完成节点与后续允许推进的节点，
     * 避免旧失败上下文污染新执行。
     * </p>
     *
     * @param job       PPT 任务信息
     * @param checkpoint 恢复起点，即最近成功完成的业务节点
     */
    void resumeFromCheckpoint(PptJob job, PptJobNode checkpoint);
}
