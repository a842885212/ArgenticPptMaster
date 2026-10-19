package domi.argenticpptmaster.engine;

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

/** Builds a Python-backed {@link PptEngineBundle} over a (often mocked) command executor. */
public final class PptEngineTestSupport {

    private PptEngineTestSupport() {
    }

    public static PptEngineBundle pythonBundle(PptMasterCommandExecutor commands) {
        return new PptEngineBundle(
                new PythonProjectWorkspace(commands),
                new PythonBeautifyAnalysisEngine(commands),
                new PythonSpeakerNotesSplitter(commands),
                new PythonSvgQualityChecker(commands),
                new PythonSvgFinalizer(commands),
                new PythonSvgToPptxExporter(commands),
                new PythonTemplateFillEngine(commands),
                new PythonNativeEnhanceEngine(commands),
                new PythonPptxMarkdownReadback(commands, PythonPptxMarkdownReadback.BEAUTIFY_SCRIPT),
                new PythonPptxMarkdownReadback(commands, PythonPptxMarkdownReadback.NATIVE_ENHANCE_SCRIPT),
                new PythonImageGenerationGateway(commands),
                new PythonNotesAudioGateway(commands));
    }
}
