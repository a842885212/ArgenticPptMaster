package domi.argenticpptmaster.web.dto;

import domi.argenticpptmaster.domain.PptSourceFile;

/**
 * PPT 源文件响应 DTO。
 * <p>
 * 用于向客户端返回上传的源文件信息，
 * 包含原始文件名、内容类型和文件大小。
 * </p>
 *
 * @param originalName 上传时的原始文件名
 * @param contentType  文件的内容类型（MIME 类型）
 * @param size         文件大小（字节数）
 */
public record SourceFileResponse(String originalName, String contentType, long size) {

    /**
     * 将领域模型 {@link PptSourceFile} 转换为响应 DTO。
     *
     * @param sourceFile PPT 源文件领域对象
     * @return 转换后的源文件响应 DTO
     */
    public static SourceFileResponse from(PptSourceFile sourceFile) {
        return new SourceFileResponse(
                sourceFile.originalName(),
                sourceFile.contentType(),
                sourceFile.size());
    }
}
