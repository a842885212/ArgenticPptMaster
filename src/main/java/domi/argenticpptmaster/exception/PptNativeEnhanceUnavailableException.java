package domi.argenticpptmaster.exception;

/** Native-enhance 功能未启用或当前部署未开放创建。 */
public class PptNativeEnhanceUnavailableException extends RuntimeException {

    public PptNativeEnhanceUnavailableException() {
        this("native-enhance-pptx workflow is disabled");
    }

    public PptNativeEnhanceUnavailableException(String message) {
        super(message);
    }
}
