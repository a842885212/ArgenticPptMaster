package domi.argenticpptmaster.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Project directory lifecycle: init, import-sources, info, validate. */
public interface ProjectWorkspace {

    EngineResult init(String projectName, String format, Path projectsDir, Duration timeout);

    EngineResult importSources(Path project, List<Path> sources, boolean copy, Duration timeout);

    EngineResult info(Path project, Duration timeout);

    EngineResult validate(Path project, Duration timeout);
}
