package domi.argenticpptmaster.service;

import domi.argenticpptmaster.security.PptAccessContext;

/**
 * 模式无关的生产化创建灰度门禁。
 * <p>
 * 异常类型、文案与拒绝遥测由 {@link ModeProductionProfile} 提供，
 * 保证各模式外观继续抛出各自既有异常。
 * </p>
 */
public final class ModeRolloutGate {

    private final ModeProductionProfile profile;

    public ModeRolloutGate(ModeProductionProfile profile) {
        this.profile = profile;
    }

    /**
     * 在任何任务 ID / 工作区 / 上传持久化之前调用。
     *
     * @param access 可信访问上下文；null 或缺身份时 fail closed
     */
    public void assertCreationAllowed(PptAccessContext access) {
        if (!profile.enabled()) {
            profile.recordCreationRejected();
            throw profile.unavailableException();
        }
        if (access == null || access.isInternalService()) {
            profile.recordCreationRejected();
            throw profile.accessDeniedException(profile.authenticatedCallerRequiredMessage());
        }
        if (access.hasRole(profile.adminRole())) {
            return;
        }
        if (profile.allowedTenants().contains(access.tenantId())) {
            return;
        }
        profile.recordCreationRejected();
        throw profile.accessDeniedException(profile.callerNotPermittedMessage());
    }

    public boolean isExecutionStopped() {
        return profile.executionStopEnabled();
    }
}
