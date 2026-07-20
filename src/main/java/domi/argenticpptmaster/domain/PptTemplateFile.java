package domi.argenticpptmaster.domain;

import java.nio.file.Path;

/**
 * 模板填充任务的唯一 PPTX 模板文件。
 *
 * @param originalName 原始文件名
 * @param contentType  上传时声明的 MIME 类型
 * @param size         文件大小（字节）
 * @param storedPath   任务工作区内的存储路径
 */
public record PptTemplateFile(String originalName, String contentType, long size, Path storedPath) {
}
