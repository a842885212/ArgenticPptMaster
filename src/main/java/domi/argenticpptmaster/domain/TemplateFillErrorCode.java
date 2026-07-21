package domi.argenticpptmaster.domain;

/** 模板填充稳定错误码，供事件与 API 映射。 */
public enum TemplateFillErrorCode {
    TEMPLATE_ANALYSIS_FAILED,
    FILL_PLAN_INVALID,
    TEMPLATE_APPLY_FAILED,
    TEMPLATE_VALIDATE_FAILED,
    TEMPLATE_FILL_UPLOAD_TOO_LARGE,
    TEMPLATE_FILL_CONCURRENCY_LIMIT,
    TEMPLATE_CONSTRAINT_INVALID,
    TEMPLATE_FILL_UNSUPPORTED_FEATURE,
    TEMPLATE_READBACK_FAILED;

    public String code() {
        return name();
    }
}
