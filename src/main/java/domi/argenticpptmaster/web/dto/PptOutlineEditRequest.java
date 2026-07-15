package domi.argenticpptmaster.web.dto;

import domi.argenticpptmaster.domain.PptOutlineEditOperation;
import domi.argenticpptmaster.domain.SlideOutline;

/** 结构化大纲编辑请求。 */
public record PptOutlineEditRequest(PptOutlineEditOperation operation, int slideNo, SlideOutline slide) {
}
