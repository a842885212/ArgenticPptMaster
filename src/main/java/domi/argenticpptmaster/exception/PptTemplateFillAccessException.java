package domi.argenticpptmaster.exception;

/** 模板填充访问被拒绝（调试令牌、灰度资格或任务授权）。 */
public class PptTemplateFillAccessException extends RuntimeException {

    public PptTemplateFillAccessException() {
        this("template-fill execution is not authorized");
    }

    public PptTemplateFillAccessException(String message) {
        super(message);
    }
}
