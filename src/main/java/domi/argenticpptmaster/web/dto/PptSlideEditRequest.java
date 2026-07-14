package domi.argenticpptmaster.web.dto;

/**
 * 用户针对逐页大纲提交的修改意见。
 *
 * @param slideNo 页码
 * @param comment 修改要求
 */
public record PptSlideEditRequest(int slideNo, String comment) {
}
