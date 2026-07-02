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
 * @param repoPath        ppt-master Python 项目仓库路径，默认 /home/zhang/PycharmProjects/ppt-master
 * @param workspacePath   Java 服务工作区路径，默认 var/ppt-master
 * @param pythonCommand   Python 解释器命令，默认 python3
 * @param commandTimeout  执行 Python 脚本的超时时间，默认 10 分钟
 */
@ConfigurationProperties(prefix = "ppt-master")
public record PptMasterProperties(
        Path repoPath,
        Path workspacePath,
        String pythonCommand,
        Duration commandTimeout) {

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
    }
}
