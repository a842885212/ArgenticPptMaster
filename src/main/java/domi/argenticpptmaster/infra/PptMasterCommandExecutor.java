package domi.argenticpptmaster.infra;

import domi.argenticpptmaster.config.PptMasterProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class PptMasterCommandExecutor {

    private final PptMasterProperties properties;

    public PptMasterCommandExecutor(PptMasterProperties properties) {
        this.properties = properties;
    }

    public CommandResult runPythonScript(String scriptRelativePath, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(properties.pythonCommand());
        command.add(properties.repoPath().resolve(scriptRelativePath).toString());
        command.addAll(arguments);
        return run(command, properties.repoPath(), properties.commandTimeout());
    }

    public CommandResult run(List<String> command, Path workDir, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        try {
            Process process = builder.start();
            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            CompletableFuture<Void> outputReader = CompletableFuture.runAsync(() -> {
                try {
                    process.getInputStream().transferTo(outputBuffer);
                } catch (IOException ex) {
                    throw new IllegalStateException("failed to read command output", ex);
                }
            });
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                outputReader.join();
                String output = outputBuffer.toString(StandardCharsets.UTF_8);
                return new CommandResult(-1, output, true);
            }
            outputReader.join();
            String output = outputBuffer.toString(StandardCharsets.UTF_8);
            return new CommandResult(process.exitValue(), output, false);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to run command: " + String.join(" ", command), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("command interrupted: " + String.join(" ", command), ex);
        }
    }

    public record CommandResult(int exitCode, String output, boolean timedOut) {

        public boolean successful() {
            return exitCode == 0 && !timedOut;
        }
    }
}
