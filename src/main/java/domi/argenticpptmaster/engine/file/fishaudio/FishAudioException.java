package domi.argenticpptmaster.engine.file.fishaudio;

/** Fish Audio HTTP / 网络错误；可恢复错误供网关重试或切换 fallback model。 */
public final class FishAudioException extends RuntimeException {

    private final int statusCode;
    private final boolean recoverable;

    public FishAudioException(String message, int statusCode, boolean recoverable) {
        super(sanitize(message));
        this.statusCode = statusCode;
        this.recoverable = recoverable;
    }

    public FishAudioException(String message, Throwable cause, boolean recoverable) {
        super(sanitize(message), cause);
        this.statusCode = 0;
        this.recoverable = recoverable;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean recoverable() {
        return recoverable;
    }

    private static String sanitize(String message) {
        if (message == null) {
            return "fish audio request failed";
        }
        // 防御消息中偶发粘贴的 Bearer token。
        return message.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._\\-]+", "Bearer ***");
    }
}
