package domi.argenticpptmaster.exception;

/** 模板填充任务不允许重复或不适用当前工作流。 */
public class PptTemplateFillConflictException extends RuntimeException {

    public PptTemplateFillConflictException(String message) {
        super(message);
    }
}
