package domi.argenticpptmaster.exception;

/** 调用方无权访问任务（跨 client 或未认证）。 */
public class PptCallerAccessException extends RuntimeException {

    public PptCallerAccessException(String message) {
        super(message);
    }
}
