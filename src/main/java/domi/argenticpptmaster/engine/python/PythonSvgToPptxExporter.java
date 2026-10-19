package domi.argenticpptmaster.engine.python;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.SvgToPptxExporter;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class PythonSvgToPptxExporter implements SvgToPptxExporter {

    public static final String SCRIPT = "skills/ppt-master/scripts/svg_to_pptx.py";

    private final PptMasterCommandExecutor commands;

    public PythonSvgToPptxExporter(PptMasterCommandExecutor commands) {
        this.commands = commands;
    }

    @Override
    public EngineResult export(Path project, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, List.of(project.toString()), timeout);
    }
}
