package domi.argenticpptmaster.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
