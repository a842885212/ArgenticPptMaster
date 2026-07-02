package domi.argenticpptmaster.exception;

/**
 * 当源文件存储操作（创建目录、写入文件等）失败时抛出的异常。
 * <p>
 * 通常由 {@link java.io.IOException} 包装而成，对应 HTTP 500 响应。
 * </p>
 */
public class PptStorageException extends RuntimeException {

    public PptStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
