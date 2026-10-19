package domi.argenticpptmaster.engine.python;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.ProjectWorkspace;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class PythonProjectWorkspace implements ProjectWorkspace {

    public static final String SCRIPT = "skills/ppt-master/scripts/project_manager.py";

    private final PptMasterCommandExecutor commands;

    public PythonProjectWorkspace(PptMasterCommandExecutor commands) {
        this.commands = commands;
    }

    @Override
    public EngineResult init(String projectName, String format, Path projectsDir, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, List.of(
                "init", projectName, "--format", format, "--dir", projectsDir.toString()), timeout);
    }

    @Override
    public EngineResult importSources(Path project, List<Path> sources, boolean copy, Duration timeout) {
        List<String> args = new ArrayList<>();
        args.add("import-sources");
        args.add(project.toString());
        for (Path source : sources) {
            args.add(source.toString());
        }
        if (copy) {
            args.add("--copy");
        }
        return PythonEngineSupport.run(commands, SCRIPT, args, timeout);
    }

    @Override
    public EngineResult info(Path project, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, List.of("info", project.toString()), timeout);
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, List.of("validate", project.toString()), timeout);
    }
}
