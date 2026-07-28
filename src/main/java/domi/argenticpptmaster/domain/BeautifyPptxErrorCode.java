package domi.argenticpptmaster.domain;

/** Beautify 服务端分析对客户端稳定暴露的错误码。 */
public enum BeautifyPptxErrorCode {
    BEAUTIFY_IDENTITY_FAILED,
    BEAUTIFY_INTAKE_FAILED,
    BEAUTIFY_INVENTORY_FAILED,
    BEAUTIFY_VALIDATION_FAILED,
    BEAUTIFY_UNSUPPORTED_CONTENT;

    public String code() {
        return name();
    }
}
