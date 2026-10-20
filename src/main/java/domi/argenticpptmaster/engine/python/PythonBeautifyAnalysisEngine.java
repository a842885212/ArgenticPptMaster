package domi.argenticpptmaster.engine.python;

import domi.argenticpptmaster.engine.BeautifyAnalysisEngine;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PythonBeautifyAnalysisEngine implements BeautifyAnalysisEngine {

    public static final String IDENTITY_SCRIPT = "skills/ppt-master/scripts/beautify_identity.py";
    public static final String INTAKE_SCRIPT = "skills/ppt-master/scripts/pptx_intake.py";
    public static final String INVENTORY_SCRIPT = "skills/ppt-master/scripts/beautify_inventory.py";

    private final PptMasterCommandExecutor commands;

    public PythonBeautifyAnalysisEngine(PptMasterCommandExecutor commands) {
        this.commands = commands;
    }

    @Override
    public EngineResult identify(Path sourcePptx, Duration timeout) {
        return PythonEngineSupport.run(commands, IDENTITY_SCRIPT, List.of(sourcePptx.toString()), timeout);
    }

    @Override
    public EngineResult intake(Path sourcePptx, Path analysisOutDir, Duration timeout) {
        return PythonEngineSupport.run(commands, INTAKE_SCRIPT,
                List.of(sourcePptx.toString(), "-o", analysisOutDir.toString()), timeout);
    }

    @Override
    public EngineResult inventory(
            Path slideLibrary,
            Optional<Path> imageManifest,
            Path inventoryOut,
            Duration timeout) {
        List<String> args = new ArrayList<>();
        args.add(slideLibrary.toString());
        imageManifest.ifPresent(path -> {
            args.add("--images");
            args.add(path.toString());
        });
        args.add("-o");
        args.add(inventoryOut.toString());
        return PythonEngineSupport.run(commands, INVENTORY_SCRIPT, args, timeout);
    }
}
