package domi.argenticpptmaster.domain;

import java.time.Instant;

/** 服务端维护的 fill plan 元数据，与 {@code analysis/fill_plan.meta.json} 对应。 */
public record TemplateFillPlanMetadata(
        int version,
        String digest,
        String status,
        String confirmationId,
        Instant approvedAt) {

    public static TemplateFillPlanMetadata draft(int version, String digest) {
        return new TemplateFillPlanMetadata(version, digest, "draft", null, null);
    }

    public TemplateFillPlanMetadata confirmed(String confirmationId, Instant approvedAt) {
        return new TemplateFillPlanMetadata(version, digest, "confirmed", confirmationId, approvedAt);
    }
}
