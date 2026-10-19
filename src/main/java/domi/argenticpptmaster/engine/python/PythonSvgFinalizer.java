package domi.argenticpptmaster.engine.python;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.SvgFinalizer;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class PythonSvgFinalizer implements SvgFinalizer {

    public static final String SCRIPT = "skills/ppt-master/scripts/finalize_svg.py";

    private final PptMasterCommandExecutor commands;

    public PythonSvgFinalizer(PptMasterCommandExecutor commands) {
        this.commands = commands;
    }

    @Override
    public EngineResult finalizeSvg(Path project, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, List.of(project.toString()), timeout);
    }
}
