package domi.argenticpptmaster.engine.file.imagegen;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code aspect_ratio} 到 OpenAI {@code size} 的固定映射表。
 * <p>
 * 仅收录 gpt-image-2 / 1K 档位中本服务承诺支持的四种比例，取值与 ppt-master
 * {@code image_backends/backend_openai.py} 的 {@code GPT_IMAGE_2_SIZES["1K"]} 一致。
 * 表外比例不发起 HTTP 请求，直接判为条目失败。
 * </p>
 */
public final class ImageSizeTable {

    private static final Map<String, String> ASPECT_RATIO_TO_SIZE = Map.of(
            "1:1", "1024x1024",
            "16:9", "1280x720",
            "4:3", "1024x768",
            "3:4", "768x1024");

    private ImageSizeTable() {
    }

    /**
     * 将清单条目的 {@code aspect_ratio} 解析为请求使用的 {@code size}。
     *
     * @param aspectRatio 清单原始值，允许大小写与首尾空白差异
     * @return 支持的尺寸字符串；不受支持时为 empty
     */
    public static Optional<String> resolve(String aspectRatio) {
        if (aspectRatio == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ASPECT_RATIO_TO_SIZE.get(normalize(aspectRatio)));
    }

    /** 受支持的比例集合（升序），用于错误提示。 */
    public static Set<String> supported() {
        return new TreeSet<>(ASPECT_RATIO_TO_SIZE.keySet());
    }

    private static String normalize(String aspectRatio) {
        return aspectRatio.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
