package domi.argenticpptmaster.domain;

/** 模板分析 slide library 的安全摘要（不含槽位全文或路径）。 */
public record TemplateFillAnalysisSummary(
        int templateSlideCount,
        int widthPx,
        int heightPx,
        String formatLabel,
        int textSlotCount,
        int tableCount,
        int chartCount,
        String analysisVersion) {
}
