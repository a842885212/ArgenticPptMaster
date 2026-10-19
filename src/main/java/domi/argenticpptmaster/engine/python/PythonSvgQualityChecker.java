package domi.argenticpptmaster.engine.python;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.SvgQualityChecker;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class PythonSvgQualityChecker implements SvgQualityChecker {

    public static final String SCRIPT = "skills/ppt-master/scripts/svg_quality_checker.py";

    private final PptMasterCommandExecutor commands;

    public PythonSvgQualityChecker(PptMasterCommandExecutor commands) {
        this.commands = commands;
    }

    @Override
    public EngineResult check(Path project, String format, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT,
                List.of(project.toString(), "--format", format), timeout);
    }
}
