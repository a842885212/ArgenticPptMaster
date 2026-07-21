package domi.argenticpptmaster.web.dto;

/** 模板填充结构化进度（不含绝对路径或原生内容原文）。 */
public record TemplateFillProgressResponse(
        Integer templateSlideCount,
        Integer planSlideCount,
        Integer validationErrorCount,
        Integer validationWarningCount,
        String exportFileName,
        Integer notesMappingCount,
        Integer tableMappingCount,
        Integer chartMappingCount,
        Integer capacityRiskCount,
        Integer fontAdjustmentCount,
        String constraintValidationStatus,
        String readbackValidationStatus,
        Integer readbackWarningCount,
        Integer readbackErrorCount) {

    public TemplateFillProgressResponse(
            Integer templateSlideCount,
            Integer planSlideCount,
            Integer validationErrorCount,
            Integer validationWarningCount,
            String exportFileName) {
        this(templateSlideCount, planSlideCount, validationErrorCount, validationWarningCount, exportFileName,
                null, null, null, null, null, null, null, null, null);
    }
}
