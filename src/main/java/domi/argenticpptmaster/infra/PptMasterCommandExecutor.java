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

/**
 * PPT Master Python 脚本命令执行器。
 * <p>
 * 封装了调用 ppt-master Python 项目的 {@link ProcessBuilder} 逻辑，
 * 支持超时控制、标准输出/错误合并读取、以及超时检测。
 * 所有外部 Python 脚本（project_manager.py、finalize_svg.py 等）均通过此类执行。
 * </p>
 */
@Component
public class PptMasterCommandExecutor {

    private final PptMasterProperties properties;

    /**
     * 构造执行器。
     *
     * @param properties PPT Master 配置，提供 Python 命令、仓库路径和超时设置
     */
    public PptMasterCommandExecutor(PptMasterProperties properties) {
        this.properties = properties;
    }

    /**
     * 执行 ppt-master 仓库中的 Python 脚本。
     * <p>
     * 脚本路径相对于 {@link PptMasterProperties#repoPath()}，命令在 repo 目录下执行。
     * </p>
     *
     * @param scriptRelativePath 脚本的相对路径，如 "skills/ppt-master/scripts/project_manager.py"
     * @param arguments          传递给脚本的命令行参数
     * @return 命令执行结果
     */
    public CommandResult runPythonScript(String scriptRelativePath, List<String> arguments) {
        return runPythonScript(scriptRelativePath, arguments, properties.commandTimeout());
    }

    public CommandResult runPythonScript(String scriptRelativePath, List<String> arguments, Duration timeout) {
        List<String> command = new ArrayList<>();
        command.add(properties.pythonCommand());
        command.add(properties.repoPath().resolve(scriptRelativePath).toString());
        command.addAll(arguments);
        return run(command, properties.repoPath(), timeout);
    }

    /**
     * 执行任意系统命令，带超时控制和输出收集。
     * <p>
     * 合并标准输出和错误流，在后台线程异步读取以避免缓冲区阻塞。
     * 超时后强制终止进程并标记 timedOut=true。
     * </p>
     *
     * @param command 命令及参数列表
     * @param workDir 工作目录
     * @param timeout 超时时长
     * @return 命令执行结果
     * @throws IllegalStateException 如果进程启动失败或线程被中断
     */
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

    /**
     * 命令执行结果。
     *
     * @param exitCode 进程退出码
     * @param output   合并的标准输出和错误输出
     * @param timedOut 是否因超时而终止
     */
    public record CommandResult(int exitCode, String output, boolean timedOut) {

        /**
         * 判断命令是否成功完成（退出码为 0 且未超时）。
         *
         * @return true 如果命令成功完成
         */
        public boolean successful() {
            return exitCode == 0 && !timedOut;
        }
    }
}
