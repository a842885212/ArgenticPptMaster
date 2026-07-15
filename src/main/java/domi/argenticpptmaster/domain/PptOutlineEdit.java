package domi.argenticpptmaster.domain;

/** 一条按页应用的结构化大纲编辑。 */
public record PptOutlineEdit(PptOutlineEditOperation operation, int slideNo, SlideOutline slide) {
    public void validate() {
        if (operation == null || slideNo <= 0) {
            throw new IllegalArgumentException("outline edit operation and slide number are required");
        }
        if (operation != PptOutlineEditOperation.DELETE) {
            if (slide == null) {
                throw new IllegalArgumentException("slide content is required");
            }
            slide.validate();
        }
    }
}
