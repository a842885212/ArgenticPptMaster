package domi.argenticpptmaster.domain;

/**
 * Native-enhance 阶段失败时使用的稳定错误码前缀。
 */
public enum NativeEnhanceErrorCode {
    NATIVE_ENHANCE_INIT_FAILED,
    NATIVE_ENHANCE_PLAN_FAILED,
    NATIVE_ENHANCE_AUDIO_FAILED,
    NATIVE_ENHANCE_VALIDATE_FAILED,
    NATIVE_ENHANCE_APPLY_FAILED,
    NATIVE_ENHANCE_OUTPUT_INVALID,
    NATIVE_ENHANCE_FFPROBE_UNAVAILABLE;

    public String code() {
        return name();
    }
}
