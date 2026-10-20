package domi.argenticpptmaster.engine.python;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.time.Duration;
import java.util.List;

final class PythonEngineSupport {

    private PythonEngineSupport() {
    }

    static EngineResult run(
            PptMasterCommandExecutor commands,
            String scriptRelativePath,
            List<String> arguments,
            Duration timeout) {
        return EngineResult.from(commands.runPythonScript(scriptRelativePath, List.copyOf(arguments), timeout));
    }
}
