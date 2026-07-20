package domi.argenticpptmaster.domain;

/**
 * PPT 任务事件类型的枚举，对应任务生命周期中的关键节点。
 *
 * <ul>
 *   <li>{@link #JOB_ACCEPTED} — 任务被接受</li>
 *   <li>{@link #SOURCE_STORED} — 源文件已存储</li>
 *   <li>{@link #PROJECT_PREPARED} — 项目工作区已准备</li>
 *   <li>{@link #CONFIRMATION_REQUIRED} — 需要人工确认</li>
 *   <li>{@link #CONFIRMATION_RECEIVED} — 已收到人工确认</li>
 *   <li>{@link #AGENT_STARTED} — AI 代理开始运行</li>
 *   <li>{@link #AGENT_MESSAGE} — 代理输出的消息（文本或工具调用）</li>
 *   <li>{@link #EXPORT_READY} — 导出文件已就绪</li>
 *   <li>{@link #JOB_FAILED} — 任务失败</li>
 *   <li>{@link #NODE_STARTED} — 业务节点开始执行</li>
 *   <li>{@link #NODE_COMPLETED} — 业务节点已完成</li>
 *   <li>{@link #NODE_FAILED} — 业务节点失败</li>
 *   <li>{@link #JOB_RESUME_ACCEPTED} — 失败任务恢复请求已被接受</li>
 *   <li>{@link #JOB_RESUME_STARTED} — 失败任务恢复已开始执行</li>
 * </ul>
 */
public enum PptJobEventType {
    JOB_ACCEPTED,
    SOURCE_STORED,
    PROJECT_PREPARED,
    CONFIRMATION_REQUIRED,
    CONFIRMATION_RECEIVED,
    AGENT_STARTED,
    AGENT_MESSAGE,
    EXPORT_READY,
    JOB_FAILED,
    NODE_STARTED,
    NODE_COMPLETED,
    NODE_FAILED,
    JOB_RESUME_ACCEPTED,
    JOB_RESUME_STARTED,
    TEMPLATE_FILL_PLAN_ACCEPTED,
    TEMPLATE_FILL_STAGE_STARTED,
    TEMPLATE_FILL_STAGE_COMPLETED
}
