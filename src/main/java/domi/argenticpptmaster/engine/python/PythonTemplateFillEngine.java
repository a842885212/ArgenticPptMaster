package domi.argenticpptmaster.engine.python;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.TemplateFillEngine;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class PythonTemplateFillEngine implements TemplateFillEngine {

    public static final String SCRIPT = "skills/ppt-master/scripts/template_fill_pptx.py";

    private final PptMasterCommandExecutor commands;

    public PythonTemplateFillEngine(PptMasterCommandExecutor commands) {
        this.commands = commands;
    }

    @Override
    public EngineResult analyze(Path templatePptx, Path slideLibraryOut, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, List.of(
                "analyze", templatePptx.toString(), "-o", slideLibraryOut.toString()), timeout);
    }

    @Override
    public EngineResult checkPlan(Path slideLibrary, Path fillPlan, Path checkReportOut, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, List.of(
                "check-plan",
                slideLibrary.toString(),
                fillPlan.toString(),
                "-o",
                checkReportOut.toString()), timeout);
    }

    @Override
    public EngineResult apply(Path templatePptx, Path fillPlan, Path exportOut, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, List.of(
                "apply",
                templatePptx.toString(),
                fillPlan.toString(),
                "-o",
                exportOut.toString()), timeout);
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, List.of("validate", project.toString()), timeout);
    }
}
