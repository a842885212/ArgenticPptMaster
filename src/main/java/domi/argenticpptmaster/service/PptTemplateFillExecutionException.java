package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.TemplateFillErrorCode;

/** 模板填充某个固定阶段执行失败。 */
public class PptTemplateFillExecutionException extends RuntimeException {

    private final String stage;
    private final TemplateFillErrorCode errorCode;

    public PptTemplateFillExecutionException(String stage, String message) {
        this(stage, message, (TemplateFillErrorCode) null);
    }

    public PptTemplateFillExecutionException(String stage, String message, TemplateFillErrorCode errorCode) {
        super(message);
        this.stage = stage;
        this.errorCode = errorCode;
    }

    public PptTemplateFillExecutionException(String stage, String message, Throwable cause) {
        this(stage, message, null, cause);
    }

    public PptTemplateFillExecutionException(
            String stage, String message, TemplateFillErrorCode errorCode, Throwable cause) {
        super(message, cause);
        this.stage = stage;
        this.errorCode = errorCode;
    }

    public String stage() {
        return stage;
    }

    public TemplateFillErrorCode errorCode() {
        return errorCode;
    }
}
