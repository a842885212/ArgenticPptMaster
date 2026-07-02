package domi.argenticpptmaster.domain;

/**
 * PPT 生成任务的完整生命周期状态枚举。
 *
 * <h3>状态流转</h3>
 * <pre>
 * ACCEPTED ──→ PREPARING ──→ WAITING_CONFIRMATION ──→ RUNNING_AGENT ──→ EXPORTING ──→ COMPLETED
 *                                                                                  ↘ FAILED
 *                                           ↖（用户拒绝确认）──────→ FAILED
 * </pre>
 *
 * <ul>
 *   <li>{@link #ACCEPTED} — 任务已创建并接受，等待处理</li>
 *   <li>{@link #PREPARING} — 正在准备项目工作区</li>
 *   <li>{@link #WAITING_CONFIRMATION} — 等待人工确认执行方案</li>
 *   <li>{@link #RUNNING_AGENT} — AI 代理正在生成内容</li>
 *   <li>{@link #EXPORTING} — 正在导出最终 PPT 文件</li>
 *   <li>{@link #COMPLETED} — 导出完成</li>
 *   <li>{@link #FAILED} — 任务失败</li>
 *   <li>{@link #CANCELLED} — 任务被取消</li>
 * </ul>
 */
public enum PptJobStatus {
    ACCEPTED,
    PREPARING,
    WAITING_CONFIRMATION,
    RUNNING_AGENT,
    EXPORTING,
    COMPLETED,
    FAILED,
    CANCELLED
}
