package domi.argenticpptmaster.exception;

/** Beautify 访问被拒绝（灰度资格或任务授权）。 */
public class PptBeautifyAccessException extends RuntimeException {

    public PptBeautifyAccessException() {
        this("beautify-pptx execution is not authorized");
    }

    public PptBeautifyAccessException(String message) {
        super(message);
    }
}
