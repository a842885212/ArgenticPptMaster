package domi.argenticpptmaster.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * PPT Master 核心功能配置属性。
 * <p>
 * 对应 {@code ppt-master.*} 前缀的 Spring Boot 配置项。
 * 用于指定 Python 脚本仓库路径、工作区路径、Python 命令及命令超时时间。
 * </p>
 *
 * @param repoPath                        ppt-master Python 项目仓库路径
 * @param workspacePath                   Java 服务工作区路径
 * @param pythonCommand                   Python 解释器命令
 * @param commandTimeout                  执行 Python 脚本的超时时间
 * @param templateFillDebugToken          模板填充调试入口令牌；为空时入口关闭
 * @param templateFillPlanMaxBytes        fill plan 请求体大小上限
 * @param templateFillTemplateMaxBytes    单模板上限；0 表示默认 50 MiB
 * @param templateFillContentMaxBytes     单内容文件上限；0 表示默认 20 MiB
 * @param templateFillTotalUploadMaxBytes 创建时总上传上限；0 表示默认 100 MiB
 * @param templateFillMaxConcurrentJobs   并发执行上限；0 表示默认 2
 * @param templateFillAnalyzeTimeout      analyze 专用超时；null 表示默认 5 分钟
 * @param templateFillEnabled             是否允许新建 TEMPLATE_FILL 任务；默认 false
 * @param templateFillAllowedTenants      灰度允许的租户集合
 * @param templateFillAdminRole           管理员角色名；默认 ADMIN
 * @param templateFillProduction          保留/清理/诊断等生产化嵌套配置；null 时使用默认值
 */
@ConfigurationProperties(prefix = "ppt-master")
public record PptMasterProperties(
        Path repoPath,
        Path workspacePath,
        String pythonCommand,
        Duration commandTimeout,
        String templateFillDebugToken,
        long templateFillPlanMaxBytes,
        long templateFillTemplateMaxBytes,
        long templateFillContentMaxBytes,
        long templateFillTotalUploadMaxBytes,
        int templateFillMaxConcurrentJobs,
        Duration templateFillAnalyzeTimeout,
        Boolean templateFillEnabled,
        List<String> templateFillAllowedTenants,
        String templateFillAdminRole,
        @NestedConfigurationProperty TemplateFillProductionProperties templateFillProduction) {

    private static final long DEFAULT_TEMPLATE_MAX_BYTES = 52_428_800L;
    private static final long DEFAULT_CONTENT_MAX_BYTES = 20_971_520L;
    private static final long DEFAULT_TOTAL_UPLOAD_MAX_BYTES = 104_857_600L;
    private static final int DEFAULT_MAX_CONCURRENT_JOBS = 2;

    public PptMasterProperties {
        if (repoPath == null) {
            repoPath = Path.of("/home/zhang/PycharmProjects/ppt-master");
        }
        if (workspacePath == null) {
            workspacePath = Path.of("var/ppt-master");
        }
        if (pythonCommand == null || pythonCommand.isBlank()) {
            pythonCommand = "python3";
        }
        if (commandTimeout == null) {
            commandTimeout = Duration.ofMinutes(10);
        }
        if (templateFillPlanMaxBytes <= 0) {
            templateFillPlanMaxBytes = 1_048_576L;
        }
        if (templateFillTemplateMaxBytes <= 0) {
            templateFillTemplateMaxBytes = DEFAULT_TEMPLATE_MAX_BYTES;
        }
        if (templateFillContentMaxBytes <= 0) {
            templateFillContentMaxBytes = DEFAULT_CONTENT_MAX_BYTES;
        }
        if (templateFillTotalUploadMaxBytes <= 0) {
            templateFillTotalUploadMaxBytes = DEFAULT_TOTAL_UPLOAD_MAX_BYTES;
        }
        if (templateFillMaxConcurrentJobs <= 0) {
            templateFillMaxConcurrentJobs = DEFAULT_MAX_CONCURRENT_JOBS;
        }
        if (templateFillAnalyzeTimeout == null) {
            templateFillAnalyzeTimeout = Duration.ofMinutes(5);
        }
        templateFillProduction = mergeProduction(
                templateFillEnabled,
                templateFillAllowedTenants,
                templateFillAdminRole,
                templateFillProduction);
        // Keep flattened fields aligned with the resolved nested production config.
        templateFillEnabled = templateFillProduction.enabled();
        templateFillAllowedTenants = templateFillProduction.allowedTenants();
        templateFillAdminRole = templateFillProduction.adminRole();
    }

    private static TemplateFillProductionProperties mergeProduction(
            Boolean enabled,
            List<String> allowedTenants,
            String adminRole,
            TemplateFillProductionProperties production) {
        TemplateFillProductionProperties base = production == null
                ? TemplateFillProductionProperties.defaults()
                : production;
        boolean resolvedEnabled = enabled != null ? enabled : base.enabled();
        List<String> resolvedTenants = allowedTenants != null ? allowedTenants : base.allowedTenants();
        String resolvedAdmin = adminRole != null && !adminRole.isBlank() ? adminRole : base.adminRole();
        return new TemplateFillProductionProperties(
                resolvedEnabled,
                resolvedTenants,
                resolvedAdmin,
                base.retentionCompleted(),
                base.retentionFailed(),
                base.retentionDiagnostic(),
                base.retentionMin(),
                base.retentionMax(),
                base.cleanupDryRunEnabled(),
                base.cleanupDeletionEnabled(),
                base.diagnosticsEnabled(),
                base.executionStopEnabled());
    }

    /** 测试与单元场景使用的最小配置（模板填充创建默认关闭）。 */
    public static PptMasterProperties forTest(Path repoPath, Path workspacePath) {
        return new PptMasterProperties(
                repoPath, workspacePath, "python3", Duration.ofSeconds(30), null, 1_048_576L,
                0, 0, 0, 0, null, null, null, null, null);
    }

    /** 测试场景：开启模板填充创建并允许给定租户。 */
    public static PptMasterProperties forTemplateFillTest(Path repoPath, Path workspacePath, String... tenants) {
        return new PptMasterProperties(
                repoPath, workspacePath, "python3", Duration.ofSeconds(30), null, 1_048_576L,
                0, 0, 0, 0, null, true, List.of(tenants), null,
                TemplateFillProductionProperties.forTestEnabled(tenants));
    }

    public boolean isTemplateFillEnabled() {
        return templateFillProduction.enabled();
    }

    @Override
    public String toString() {
        return "PptMasterProperties[repoPath=" + repoPath
                + ", workspacePath=" + workspacePath
                + ", pythonCommand=" + pythonCommand
                + ", commandTimeout=" + commandTimeout
                + ", templateFillDebugEnabled=" + (templateFillDebugToken != null && !templateFillDebugToken.isBlank())
                + ", templateFillPlanMaxBytes=" + templateFillPlanMaxBytes
                + ", templateFillTemplateMaxBytes=" + templateFillTemplateMaxBytes
                + ", templateFillContentMaxBytes=" + templateFillContentMaxBytes
                + ", templateFillTotalUploadMaxBytes=" + templateFillTotalUploadMaxBytes
                + ", templateFillMaxConcurrentJobs=" + templateFillMaxConcurrentJobs
                + ", templateFillAnalyzeTimeout=" + templateFillAnalyzeTimeout
                + ", templateFillEnabled=" + templateFillProduction.enabled()
                + ", templateFillAllowedTenantCount=" + templateFillProduction.allowedTenants().size()
                + ", templateFillAdminRole=" + templateFillProduction.adminRole()
                + ", templateFillCleanupDryRunEnabled=" + templateFillProduction.cleanupDryRunEnabled()
                + ", templateFillCleanupDeletionEnabled=" + templateFillProduction.cleanupDeletionEnabled()
                + ", templateFillDiagnosticsEnabled=" + templateFillProduction.diagnosticsEnabled()
                + ", templateFillExecutionStopEnabled=" + templateFillProduction.executionStopEnabled()
                + "]";
    }
}
