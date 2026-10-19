package domi.argenticpptmaster.engine.python;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.NativeEnhanceEngine;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class PythonNativeEnhanceEngine implements NativeEnhanceEngine {

    public static final String SCRIPT = "skills/ppt-master/scripts/native_enhance_pptx.py";

    private final PptMasterCommandExecutor commands;

    public PythonNativeEnhanceEngine(PptMasterCommandExecutor commands) {
        this.commands = commands;
    }

    @Override
    public EngineResult init(
            Path sourcePptx,
            String name,
            Path projectDir,
            Path projectsRoot,
            Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, List.of(
                "init",
                sourcePptx.toString(),
                "--name",
                name,
                "--project-dir",
                projectDir.toString(),
                "--projects-root",
                projectsRoot.toString()), timeout);
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, List.of("validate", project.toString()), timeout);
    }

    @Override
    public EngineResult apply(List<String> arguments, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, arguments, timeout);
    }
}
