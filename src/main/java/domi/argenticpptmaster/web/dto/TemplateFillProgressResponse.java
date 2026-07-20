package domi.argenticpptmaster.web.dto;

/** 模板填充结构化进度（不含绝对路径）。 */
public record TemplateFillProgressResponse(
        Integer templateSlideCount,
        Integer planSlideCount,
        Integer validationErrorCount,
        Integer validationWarningCount,
        String exportFileName) {
}
