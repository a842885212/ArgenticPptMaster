package domi.argenticpptmaster.engine;

/**
 * Capability engines available to agent tools and service executors.
 *
 * <p>Callers use this facade instead of {@code PptMasterCommandExecutor.runPythonScript}.</p>
 */
public record PptEngineBundle(
        ProjectWorkspace projectWorkspace,
        BeautifyAnalysisEngine beautifyAnalysis,
        SpeakerNotesSplitter speakerNotesSplitter,
        SvgQualityChecker svgQualityChecker,
        SvgFinalizer svgFinalizer,
        SvgToPptxExporter svgToPptxExporter,
        TemplateFillEngine templateFillEngine,
        NativeEnhanceEngine nativeEnhanceEngine,
        PptxMarkdownReadback beautifyReadback,
        PptxMarkdownReadback nativeEnhanceReadback,
        ImageGenerationGateway imageGeneration,
        NotesAudioGateway notesAudio) {
}
