package domi.argenticpptmaster.engine.file.imagegen;

/**
 * 单条图片生成失败的统一异常。
 * <p>
 * 由 {@link OpenAiImageClient} 与出图网关抛出，消息文本会写入清单条目的
 * {@code last_error}（截断至 500 字符），因此消息需自带可定位问题的上下文，
 * 且不得包含 API 密钥等敏感内容。
 * </p>
 */
public class ImageGenerationException extends RuntimeException {

    public ImageGenerationException(String message) {
        super(message);
    }

    public ImageGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
