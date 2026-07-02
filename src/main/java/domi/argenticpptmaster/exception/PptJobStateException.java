package domi.argenticpptmaster.exception;

/**
 * 当操作与当前任务状态不兼容时抛出的异常。
 * <p>
 * 例如：在非等待确认状态下提交确认、源文件扩展名不合法等。
 * 对应 HTTP 400 响应。
 * </p>
 */
public class PptJobStateException extends RuntimeException {

    public PptJobStateException(String message) {
        super(message);
    }
}
