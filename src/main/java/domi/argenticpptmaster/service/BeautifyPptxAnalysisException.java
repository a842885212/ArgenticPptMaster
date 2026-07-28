package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.BeautifyPptxErrorCode;
import domi.argenticpptmaster.domain.BeautifyPptxFailureKind;

/** Beautify 服务端分析固定阶段的安全失败表示。 */
public class BeautifyPptxAnalysisException extends RuntimeException {

    private final String stage;
    private final BeautifyPptxErrorCode errorCode;
    private final BeautifyPptxFailureKind failureKind;

    public BeautifyPptxAnalysisException(
            String stage, BeautifyPptxErrorCode errorCode, BeautifyPptxFailureKind failureKind, String message) {
        super(message);
        this.stage = stage;
        this.errorCode = errorCode;
        this.failureKind = failureKind;
    }

    public BeautifyPptxAnalysisException(
            String stage, BeautifyPptxErrorCode errorCode, BeautifyPptxFailureKind failureKind,
            String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
        this.errorCode = errorCode;
        this.failureKind = failureKind;
    }

    public String stage() {
        return stage;
    }

    public BeautifyPptxErrorCode errorCode() {
        return errorCode;
    }

    public BeautifyPptxFailureKind failureKind() {
        return failureKind;
    }
}
