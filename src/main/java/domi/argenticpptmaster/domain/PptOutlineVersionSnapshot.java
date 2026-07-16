package domi.argenticpptmaster.domain;

import java.time.Instant;

/** 工作区中保存的大纲版本快照。 */
public record PptOutlineVersionSnapshot(
        int version,
        Integer parentVersion,
        boolean locked,
        PptOutline outline,
        PptOutlineVersionDiff diff,
        String createdAt) {

    public PptOutlineVersionSnapshot {
        if (version <= 0 || outline == null || outline.version() != version) {
            throw new IllegalArgumentException("outline snapshot is invalid");
        }
        if (diff != null && diff.toVersion() != version) {
            throw new IllegalArgumentException("outline snapshot diff does not match version");
        }
    }

    public static PptOutlineVersionSnapshot initial(PptOutline outline) {
        return new PptOutlineVersionSnapshot(outline.version(), null, outline.locked(), outline, null,
                Instant.now().toString());
    }
}
