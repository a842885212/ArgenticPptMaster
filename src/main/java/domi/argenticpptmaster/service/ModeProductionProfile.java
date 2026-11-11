package domi.argenticpptmaster.service;

import java.util.Collection;

/**
 * 生产化模式配置与异常工厂的最小横切面。
 * <p>
 * 灰度门禁先使用本接口；后续 Lifecycle / Cleanup / Audit / Telemetry
 * 可在各自共享实现中扩展同模式的 profile，而不必把全部能力塞进一个上帝接口。
 * </p>
 */
public interface ModeProductionProfile {

    boolean enabled();

    /** @deprecated 外部调用方不再使用管理员角色旁路；保留供配置兼容。 */
    String adminRole();

    /**
     * 允许创建该模式的 clientId 集合。
     * <p>实现可继续读取历史 {@code allowedTenants} 配置字段。</p>
     */
    Collection<String> allowedClientIds();

    boolean executionStopEnabled();

    String authenticatedCallerRequiredMessage();

    String callerNotPermittedMessage();

    RuntimeException unavailableException();

    RuntimeException accessDeniedException(String message);

    void recordCreationRejected();
}
