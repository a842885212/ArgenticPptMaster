package domi.argenticpptmaster.domain;

/**
 * 已校验的逐页大纲修改意见。
 *
 * @param slideNo 页码
 * @param comment 修改要求
 */
public record PptSlideEdit(int slideNo, String comment) {
}
