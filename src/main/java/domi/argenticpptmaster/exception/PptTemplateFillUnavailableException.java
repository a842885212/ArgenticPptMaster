package domi.argenticpptmaster.exception;

/** 模板填充功能未启用或当前部署未开放创建。 */
public class PptTemplateFillUnavailableException extends RuntimeException {

    public PptTemplateFillUnavailableException() {
        this("template-fill workflow is disabled");
    }

    public PptTemplateFillUnavailableException(String message) {
        super(message);
    }
}
