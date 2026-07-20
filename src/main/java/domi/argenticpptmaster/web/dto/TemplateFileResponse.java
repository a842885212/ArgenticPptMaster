package domi.argenticpptmaster.web.dto;

import domi.argenticpptmaster.domain.PptTemplateFile;

/** 模板填充任务的模板文件安全元数据。 */
public record TemplateFileResponse(String originalName, String contentType, long size, String role) {

    public static TemplateFileResponse from(PptTemplateFile template) {
        return new TemplateFileResponse(template.originalName(), template.contentType(), template.size(), "template");
    }
}
