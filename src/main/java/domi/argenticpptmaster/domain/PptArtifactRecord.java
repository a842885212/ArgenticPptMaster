package domi.argenticpptmaster.domain;

/** 受控下游产物的来源版本登记。 */
public record PptArtifactRecord(String path, int outlineVersion, PptArtifactStatus status) {
    public PptArtifactRecord {
        if (path == null || path.isBlank() || outlineVersion <= 0 || status == null) {
            throw new IllegalArgumentException("artifact record is invalid");
        }
    }

    public PptArtifactRecord stale() {
        return new PptArtifactRecord(path, outlineVersion, PptArtifactStatus.STALE);
    }
}
