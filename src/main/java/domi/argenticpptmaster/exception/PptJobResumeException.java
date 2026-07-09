package domi.argenticpptmaster.exception;

import java.util.UUID;

/**
 * 任务恢复异常。
 *
 * <p>
 * 当调用方尝试恢复一个不可恢复的任务时抛出，例如任务状态不是 {@link domi.argenticpptmaster.domain.PptJobStatus#FAILED}、
 * 任务仍在等待人工确认、或不存在任何可恢复的成功节点。
 * </p>
 *
 * @author zhangtianhao
 * @since 2026-07-09
 */
public class PptJobResumeException extends RuntimeException {

    private final UUID jobId;

    public PptJobResumeException(UUID jobId, String message) {
        super(message);
        this.jobId = jobId;
    }

    public UUID jobId() {
        return jobId;
    }
}
