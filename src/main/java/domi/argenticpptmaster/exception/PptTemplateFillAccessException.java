package domi.argenticpptmaster.exception;

/** 模板填充调试入口未启用或访问令牌不匹配。 */
public class PptTemplateFillAccessException extends RuntimeException {

    public PptTemplateFillAccessException() {
        super("template-fill execution is not authorized");
    }
}
