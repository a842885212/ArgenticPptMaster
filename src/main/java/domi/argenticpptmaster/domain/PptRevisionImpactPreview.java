package domi.argenticpptmaster.domain;

import java.time.Instant;
import java.util.List;

/** 锁定大纲再次修订前的影响预览。 */
public record PptRevisionImpactPreview(
        String revisionImpactToken,
        int outlineVersion,
        List<String> affectedArtifacts,
        Instant expiresAt) {
    public PptRevisionImpactPreview {
        if (revisionImpactToken == null || revisionImpactToken.isBlank() || outlineVersion <= 0
                || expiresAt == null) {
            throw new IllegalArgumentException("revision impact preview is invalid");
        }
        affectedArtifacts = affectedArtifacts == null ? List.of() : List.copyOf(affectedArtifacts);
    }
}
