package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.NativeEnhanceErrorCode;

/**
 * Native-enhance 命令编排失败。
 */
public class NativeEnhanceExecutionException extends RuntimeException {

    private final String stage;
    private final NativeEnhanceErrorCode errorCode;

    public NativeEnhanceExecutionException(String stage, NativeEnhanceErrorCode errorCode, String message) {
        super(message);
        this.stage = stage;
        this.errorCode = errorCode;
    }

    public NativeEnhanceExecutionException(
            String stage, NativeEnhanceErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
        this.errorCode = errorCode;
    }

    public String stage() {
        return stage;
    }

    public NativeEnhanceErrorCode errorCode() {
        return errorCode;
    }
}
