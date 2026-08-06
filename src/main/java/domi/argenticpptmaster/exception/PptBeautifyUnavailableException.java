package domi.argenticpptmaster.exception;

/** Beautify 功能未启用或当前部署未开放创建。 */
public class PptBeautifyUnavailableException extends RuntimeException {

    public PptBeautifyUnavailableException() {
        this("beautify-pptx workflow is disabled");
    }

    public PptBeautifyUnavailableException(String message) {
        super(message);
    }
}
