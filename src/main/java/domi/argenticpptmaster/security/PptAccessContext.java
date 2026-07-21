package domi.argenticpptmaster.security;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 不可伪造的访问上下文。仅允许由部署认证适配器或受信测试桩构造。
 */
public record PptAccessContext(
        String subjectId,
        String tenantId,
        Set<String> roles,
        boolean internalService) {

    public PptAccessContext {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(tenantId, "tenantId");
        if (subjectId.isBlank() || tenantId.isBlank()) {
            throw new IllegalArgumentException("subjectId and tenantId must be non-blank");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static PptAccessContext user(String subjectId, String tenantId, Set<String> roles) {
        return new PptAccessContext(subjectId, tenantId, roles, false);
    }

    public static PptAccessContext forInternalService() {
        return new PptAccessContext("internal-service", "system", Set.of(), true);
    }

    public boolean isInternalService() {
        return internalService;
    }

    public boolean hasRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String expected = role.trim().toUpperCase(Locale.ROOT);
        return roles.stream().anyMatch(r -> r != null && expected.equals(r.trim().toUpperCase(Locale.ROOT)));
    }
}
