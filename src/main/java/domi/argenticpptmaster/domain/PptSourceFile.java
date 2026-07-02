package domi.argenticpptmaster.domain;

import java.nio.file.Path;

/**
 * PPT 任务上传的源文件信息。
 *
 * @param originalName 原始文件名
 * @param contentType  MIME 类型
 * @param size         文件大小（字节）
 * @param storedPath   存储到磁盘后的绝对路径
 */
public record PptSourceFile(String originalName, String contentType, long size, Path storedPath) {
}
