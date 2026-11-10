package domi.argenticpptmaster.domain;

/**
 * PPT 任务的确认推进模式。
 * <p>
 * 创建时写入且不可变：
 * </p>
 * <ul>
 *   <li>{@link #INTERACTIVE} —— 一次性确认门禁进入人工等待。</li>
 *   <li>{@link #AUTO} —— 一次性确认门禁按当前草案自动批准，不进入人工等待。</li>
 * </ul>
 */
public enum PptConfirmationMode {

    INTERACTIVE,

    AUTO;

    /**
     * 将字符串规范化为确认模式。
     * <p>空值默认 {@link #INTERACTIVE}；无法识别的非空值会被拒绝。</p>
     */
    public static PptConfirmationMode from(String value) {
        if (value == null || value.isBlank()) {
            return INTERACTIVE;
        }
        String normalized = value.trim().toLowerCase().replace('_', '-');
        return switch (normalized) {
            case "auto" -> AUTO;
            case "interactive", "hitl", "manual" -> INTERACTIVE;
            default -> throw new IllegalArgumentException("unsupported confirmation mode: " + value);
        };
    }
}
