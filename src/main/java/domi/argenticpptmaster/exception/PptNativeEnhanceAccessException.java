package domi.argenticpptmaster.exception;

/** Native-enhance 访问被拒绝（灰度资格或任务授权）。 */
public class PptNativeEnhanceAccessException extends RuntimeException {

    public PptNativeEnhanceAccessException() {
        this("native-enhance-pptx execution is not authorized");
    }

    public PptNativeEnhanceAccessException(String message) {
        super(message);
    }
}
