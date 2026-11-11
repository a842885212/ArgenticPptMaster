package domi.argenticpptmaster.security;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.exception.PptBeautifyAccessException;
import domi.argenticpptmaster.exception.PptCallerAccessException;
import domi.argenticpptmaster.exception.PptNativeEnhanceAccessException;
import domi.argenticpptmaster.exception.PptTemplateFillAccessException;
import org.springframework.stereotype.Component;

/**
 * 任务外部访问的统一授权：按服务调用方 {@code clientId} 隔离。
 * <p>
 * 归属字段 owner/tenant 均绑定创建时的 clientId；仅同 client 或内部服务可访问。
 * </p>
 */
@Component
public class PptJobAccessAuthorizer {

    private final PptAccessContextResolver accessContextResolver;

    public PptJobAccessAuthorizer(
            PptMasterProperties properties,
            PptAccessContextResolver accessContextResolver) {
        this.accessContextResolver = accessContextResolver;
    }

    /** 对任务强制按 client 授权。 */
    public void assertCanAccess(PptJob job) {
        assertCanAccess(job, accessContextResolver.resolve().orElse(null));
    }

    public void assertCanAccess(PptJob job, PptAccessContext access) {
        if (job == null) {
            return;
        }
        if (access == null) {
            throw accessDenied(job, "job access requires authenticated caller");
        }
        if (access.isInternalService()) {
            return;
        }
        if (!job.hasOwnership()) {
            throw accessDenied(job, "job ownership is missing");
        }
        String owner = job.ownerSubjectId().orElse("");
        String tenant = job.ownerTenantId().orElse("");
        if (owner.equals(access.subjectId()) && tenant.equals(access.tenantId())) {
            return;
        }
        throw accessDenied(job, "job access is denied for this caller");
    }

    private static RuntimeException accessDenied(PptJob job, String message) {
        return switch (job.workflowMode()) {
            case TEMPLATE_FILL -> new PptTemplateFillAccessException(message);
            case BEAUTIFY_PPTX -> new PptBeautifyAccessException(message);
            case NATIVE_ENHANCE_PPTX -> new PptNativeEnhanceAccessException(message);
            default -> new PptCallerAccessException(message);
        };
    }
}
