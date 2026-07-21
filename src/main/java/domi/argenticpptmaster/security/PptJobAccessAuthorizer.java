package domi.argenticpptmaster.security;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptTemplateFillAccessException;
import org.springframework.stereotype.Component;

/**
 * 模板填充任务外部访问的统一授权。
 * <p>
 * 仅同 tenant 的 owner 或管理员可访问；内部服务身份仅供异步 runner。
 * </p>
 */
@Component
public class PptJobAccessAuthorizer {

    private final PptMasterProperties properties;
    private final PptAccessContextResolver accessContextResolver;

    public PptJobAccessAuthorizer(
            PptMasterProperties properties,
            PptAccessContextResolver accessContextResolver) {
        this.properties = properties;
        this.accessContextResolver = accessContextResolver;
    }

    /** 对 TEMPLATE_FILL 任务强制授权；其他模式直接放行。 */
    public void assertCanAccess(PptJob job) {
        assertCanAccess(job, accessContextResolver.resolve().orElse(null));
    }

    public void assertCanAccess(PptJob job, PptAccessContext access) {
        if (job == null || job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            return;
        }
        if (access == null) {
            throw new PptTemplateFillAccessException("template-fill job access requires authentication");
        }
        if (access.isInternalService()) {
            return;
        }
        String adminRole = properties.templateFillProduction().adminRole();
        if (access.hasRole(adminRole)) {
            return;
        }
        if (!job.hasOwnership()) {
            throw new PptTemplateFillAccessException("template-fill job ownership is missing");
        }
        String owner = job.ownerSubjectId().orElse("");
        String tenant = job.ownerTenantId().orElse("");
        if (owner.equals(access.subjectId()) && tenant.equals(access.tenantId())) {
            return;
        }
        throw new PptTemplateFillAccessException("template-fill job access is denied");
    }
}
