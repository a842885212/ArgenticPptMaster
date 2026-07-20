package domi.argenticpptmaster.service;

/** 模板填充某个固定阶段执行失败。 */
public class PptTemplateFillExecutionException extends RuntimeException {

    private final String stage;

    public PptTemplateFillExecutionException(String stage, String message) {
        super(message);
        this.stage = stage;
    }

    public PptTemplateFillExecutionException(String stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    public String stage() {
        return stage;
    }
}
