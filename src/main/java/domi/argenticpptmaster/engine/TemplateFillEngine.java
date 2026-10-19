package domi.argenticpptmaster.engine;

import java.nio.file.Path;
import java.time.Duration;

/** OOXML template-fill stages over an existing PPTX. */
public interface TemplateFillEngine {

    EngineResult analyze(Path templatePptx, Path slideLibraryOut, Duration timeout);

    EngineResult checkPlan(Path slideLibrary, Path fillPlan, Path checkReportOut, Duration timeout);

    EngineResult apply(Path templatePptx, Path fillPlan, Path exportOut, Duration timeout);

    EngineResult validate(Path project, Duration timeout);
}
