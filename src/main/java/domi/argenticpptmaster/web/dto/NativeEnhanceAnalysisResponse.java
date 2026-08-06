package domi.argenticpptmaster.web.dto;

import java.util.List;
import java.util.Map;

/** 客户端可见的 native-enhance 安全摘要，不包含工作区绝对路径、备注全文或音频路径。 */
public record NativeEnhanceAnalysisResponse(
        Integer sourcePageCount,
        String enhancementPlanStatus,
        List<String> enabledModules,
        Map<String, Object> notesCoverage,
        Map<String, Object> audioCoverage,
        String audioVoiceStatus,
        Map<String, Object> transitionSummary,
        Map<String, Object> validateReportSummary,
        Map<String, Object> outputValidationSummary,
        String exportFileName) {
}
