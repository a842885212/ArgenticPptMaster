package domi.argenticpptmaster.exception;

import java.util.UUID;

/**
 * 当根据 ID 查找 PPT 任务但不存在时抛出的异常。
 * <p>
 * 对应 HTTP 404 响应。
 * </p>
 */
public class PptJobNotFoundException extends RuntimeException {

    public PptJobNotFoundException(UUID jobId) {
        super("PPT job not found: " + jobId);
    }
}
