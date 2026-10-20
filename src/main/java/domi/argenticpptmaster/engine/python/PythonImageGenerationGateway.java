package domi.argenticpptmaster.engine.python;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.ImageGenerationGateway;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class PythonImageGenerationGateway implements ImageGenerationGateway {

    public static final String SCRIPT = "skills/ppt-master/scripts/image_gen.py";

    private final PptMasterCommandExecutor commands;

    public PythonImageGenerationGateway(PptMasterCommandExecutor commands) {
        this.commands = commands;
    }

    @Override
    public EngineResult generateFromManifest(Path manifest, Path outputDir, Duration timeout) {
        return PythonEngineSupport.run(commands, SCRIPT, List.of(
                "--manifest", manifest.toString(),
                "--output", outputDir.toString()), timeout);
    }
}
