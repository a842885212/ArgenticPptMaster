package domi.argenticpptmaster.security;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptBeautifyAccessException;
import domi.argenticpptmaster.exception.PptTemplateFillAccessException;
import org.springframework.stereotype.Component;

/**
 * 敏感模式任务外部访问的统一授权。
 * <p>
 * TEMPLATE_FILL / BEAUTIFY_PPTX 分别读取各自 admin role；仅同 tenant 的 owner 或管理员可访问。
 * 内部服务身份仅供异步 runner。
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

    /** 对敏感模式任务强制授权；其他模式直接放行。 */
    public void assertCanAccess(PptJob job) {
        assertCanAccess(job, accessContextResolver.resolve().orElse(null));
    }

    public void assertCanAccess(PptJob job, PptAccessContext access) {
        if (job == null) {
            return;
        }
        if (job.workflowMode() == PptWorkflowMode.TEMPLATE_FILL) {
            assertTemplateFillAccess(job, access);
            return;
        }
        if (job.workflowMode() == PptWorkflowMode.BEAUTIFY_PPTX) {
            assertBeautifyAccess(job, access);
        }
    }

    private void assertTemplateFillAccess(PptJob job, PptAccessContext access) {
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

    private void assertBeautifyAccess(PptJob job, PptAccessContext access) {
        if (access == null) {
            throw new PptBeautifyAccessException("beautify-pptx job access requires authentication");
        }
        if (access.isInternalService()) {
            return;
        }
        String adminRole = properties.beautifyPptx().production().adminRole();
        if (access.hasRole(adminRole)) {
            return;
        }
        if (!job.hasOwnership()) {
            throw new PptBeautifyAccessException("beautify-pptx job ownership is missing");
        }
        String owner = job.ownerSubjectId().orElse("");
        String tenant = job.ownerTenantId().orElse("");
        if (owner.equals(access.subjectId()) && tenant.equals(access.tenantId())) {
            return;
        }
        throw new PptBeautifyAccessException("beautify-pptx job access is denied");
    }
}
