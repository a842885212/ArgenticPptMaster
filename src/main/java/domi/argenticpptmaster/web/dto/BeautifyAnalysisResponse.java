package domi.argenticpptmaster.web.dto;

import java.util.Map;

/** 客户端可见的 beautify 分析安全摘要，不包含工作区路径或冻结内容账本。 */
public record BeautifyAnalysisResponse(
        Integer sourcePageCount,
        Map<String, Object> canvas,
        String resolvedFormat,
        boolean identityReady,
        boolean inventoryReady,
        boolean slideLibraryReady,
        Integer complexObjectAlertCount,
        String beautifyPlanStatus,
        Map<String, Object> outputValidationSummary,
        Integer imageCount,
        Integer tableCount,
        Integer chartCount,
        Boolean imageManifestReady) {
}
