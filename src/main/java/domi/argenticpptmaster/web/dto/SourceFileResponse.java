package domi.argenticpptmaster.web.dto;

import domi.argenticpptmaster.domain.PptSourceFile;

public record SourceFileResponse(String originalName, String contentType, long size) {

    public static SourceFileResponse from(PptSourceFile sourceFile) {
        return new SourceFileResponse(
                sourceFile.originalName(),
                sourceFile.contentType(),
                sourceFile.size());
    }
}
