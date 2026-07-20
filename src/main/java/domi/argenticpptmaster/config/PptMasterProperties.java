package domi.argenticpptmaster.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PPT Master 核心功能配置属性。
 * <p>
 * 对应 {@code ppt-master.*} 前缀的 Spring Boot 配置项。
 * 用于指定 Python 脚本仓库路径、工作区路径、Python 命令及命令超时时间。
 * </p>
 *
 * @param repoPath                       ppt-master Python 项目仓库路径
 * @param workspacePath                  Java 服务工作区路径
 * @param pythonCommand                  Python 解释器命令
 * @param commandTimeout                 执行 Python 脚本的超时时间
 * @param templateFillDebugToken         模板填充调试入口令牌；为空时入口关闭
 * @param templateFillPlanMaxBytes       fill plan 请求体大小上限
 * @param templateFillTemplateMaxBytes   单模板上限；0 表示默认 50 MiB
 * @param templateFillContentMaxBytes    单内容文件上限；0 表示默认 20 MiB
 * @param templateFillTotalUploadMaxBytes 创建时总上传上限；0 表示默认 100 MiB
 * @param templateFillMaxConcurrentJobs  并发执行上限；0 表示默认 2
 * @param templateFillAnalyzeTimeout     analyze 专用超时；null 表示默认 5 分钟
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
        Duration templateFillAnalyzeTimeout) {

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
    }

    /** 测试与单元场景使用的最小配置（其余字段走默认值）。 */
    public static PptMasterProperties forTest(Path repoPath, Path workspacePath) {
        return new PptMasterProperties(
                repoPath, workspacePath, "python3", Duration.ofSeconds(30), null, 1_048_576L,
                0, 0, 0, 0, null);
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
                + ", templateFillAnalyzeTimeout=" + templateFillAnalyzeTimeout + "]";
    }
}
