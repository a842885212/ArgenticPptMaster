package domi.argenticpptmaster.domain;

/**
 * PPT 任务业务节点的执行状态。
 *
 * <p>
 * 用于描述某个 {@link PptJobNode} 在任务生命周期中的当前状态。
 * 每个节点都会从 {@link #PENDING} 开始，经历运行、暂停或完成，最终落在
 * {@link #COMPLETED}、{@link #FAILED} 或 {@link #SKIPPED} 之一。
 * </p>
 *
 * @author zhangtianhao
 * @since 2026-07-09
 */
public enum PptJobNodeStatus {

    /**
     * 节点尚未开始执行。
     */
    PENDING,

    /**
     * 节点正在执行中。
     */
    RUNNING,

    /**
     * 节点已暂停，等待外部确认或人工决策。
     */
    WAITING_CONFIRMATION,

    /**
     * 节点已成功完成。
     */
    COMPLETED,

    /**
     * 节点执行失败。
     */
    FAILED,

    /**
     * 节点被跳过（例如当前工作流模式不适用）。
     */
    SKIPPED
}
