package domi.argenticpptmaster.domain;

/**
 * Native-enhance 阶段失败时使用的稳定错误码前缀。
 */
public enum NativeEnhanceErrorCode {
    NATIVE_ENHANCE_INIT_FAILED,
    NATIVE_ENHANCE_PLAN_FAILED,
    NATIVE_ENHANCE_VALIDATE_FAILED,
    NATIVE_ENHANCE_APPLY_FAILED,
    NATIVE_ENHANCE_OUTPUT_INVALID;

    public String code() {
        return name();
    }
}
