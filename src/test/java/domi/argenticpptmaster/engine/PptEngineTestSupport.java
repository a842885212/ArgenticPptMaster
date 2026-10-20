package domi.argenticpptmaster.engine;

import domi.argenticpptmaster.engine.file.JavaNativeEnhanceEngine;
import domi.argenticpptmaster.engine.file.JavaTemplateFillEngine;
import domi.argenticpptmaster.engine.file.svgquality.JavaSvgQualityChecker;
import domi.argenticpptmaster.engine.python.PythonBeautifyAnalysisEngine;
import domi.argenticpptmaster.engine.python.PythonImageGenerationGateway;
import domi.argenticpptmaster.engine.python.PythonNativeEnhanceEngine;
import domi.argenticpptmaster.engine.python.PythonNotesAudioGateway;
import domi.argenticpptmaster.engine.python.PythonPptxMarkdownReadback;
import domi.argenticpptmaster.engine.python.PythonProjectWorkspace;
import domi.argenticpptmaster.engine.python.PythonSpeakerNotesSplitter;
import domi.argenticpptmaster.engine.python.PythonSvgFinalizer;
import domi.argenticpptmaster.engine.python.PythonSvgQualityChecker;
import domi.argenticpptmaster.engine.python.PythonSvgToPptxExporter;
import domi.argenticpptmaster.engine.python.PythonTemplateFillEngine;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;

/**
 * Builds a capability-engine {@link PptEngineBundle} over a (often mocked) command executor.
 *
 * <p>Template-fill / native-enhance / svg-quality use Routing beans with Python as the
 * production authority so tests can keep mocking {@code runPythonScript}.</p>
 */
public final class PptEngineTestSupport {

    private PptEngineTestSupport() {
    }

    public static PptEngineBundle pythonBundle(PptMasterCommandExecutor commands) {
        return new PptEngineBundle(
                new PythonProjectWorkspace(commands),
                new PythonBeautifyAnalysisEngine(commands),
                new PythonSpeakerNotesSplitter(commands),
                new RoutingSvgQualityChecker(
                        new JavaSvgQualityChecker(), new PythonSvgQualityChecker(commands)),
                new PythonSvgFinalizer(commands),
                new PythonSvgToPptxExporter(commands),
                new RoutingTemplateFillEngine(
                        new JavaTemplateFillEngine(), new PythonTemplateFillEngine(commands)),
                new RoutingNativeEnhanceEngine(
                        new JavaNativeEnhanceEngine(), new PythonNativeEnhanceEngine(commands)),
                new PythonPptxMarkdownReadback(commands, PythonPptxMarkdownReadback.BEAUTIFY_SCRIPT),
                new PythonPptxMarkdownReadback(commands, PythonPptxMarkdownReadback.NATIVE_ENHANCE_SCRIPT),
                new PythonImageGenerationGateway(commands),
                new PythonNotesAudioGateway(commands));
    }
}
