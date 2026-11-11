package domi.argenticpptmaster.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务级 chat token usage 快照（totals + 按 model 分项）。
 *
 * @param inputTokens  累计 input tokens
 * @param outputTokens 累计 output tokens
 * @param totalTokens  input + output
 * @param byModel      按实际成功模型名称分组的累计；无调用时为空映射
 */
public record PptTokenUsage(
        long inputTokens,
        long outputTokens,
        long totalTokens,
        Map<String, PptModelTokenUsage> byModel) {

    public PptTokenUsage {
        byModel = byModel == null || byModel.isEmpty() ? Map.of() : Map.copyOf(byModel);
    }

    public static PptTokenUsage empty() {
        return new PptTokenUsage(0L, 0L, 0L, Map.of());
    }

    /** 便于测试与对账：把 byModel 分项加总。 */
    public static PptTokenUsage fromByModel(Map<String, PptModelTokenUsage> byModel) {
        if (byModel == null || byModel.isEmpty()) {
            return empty();
        }
        Map<String, PptModelTokenUsage> copy = new LinkedHashMap<>();
        long input = 0L;
        long output = 0L;
        for (Map.Entry<String, PptModelTokenUsage> entry : byModel.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            PptModelTokenUsage modelUsage = entry.getValue();
            copy.put(entry.getKey(), modelUsage);
            input += modelUsage.inputTokens();
            output += modelUsage.outputTokens();
        }
        return new PptTokenUsage(input, output, input + output, copy);
    }
}
