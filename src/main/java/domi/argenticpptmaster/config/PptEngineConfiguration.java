package domi.argenticpptmaster.config;

import domi.argenticpptmaster.engine.BeautifyAnalysisEngine;
import domi.argenticpptmaster.engine.ImageGenerationGateway;
import domi.argenticpptmaster.engine.NativeEnhanceEngine;
import domi.argenticpptmaster.engine.NotesAudioGateway;
import domi.argenticpptmaster.engine.PptEngineBundle;
import domi.argenticpptmaster.engine.PptxMarkdownReadback;
import domi.argenticpptmaster.engine.ProjectWorkspace;
import domi.argenticpptmaster.engine.RoutingBeautifyAnalysisEngine;
import domi.argenticpptmaster.engine.RoutingNativeEnhanceEngine;
import domi.argenticpptmaster.engine.RoutingProjectWorkspace;
import domi.argenticpptmaster.engine.RoutingSvgQualityChecker;
import domi.argenticpptmaster.engine.RoutingTemplateFillEngine;
import domi.argenticpptmaster.engine.SpeakerNotesSplitter;
import domi.argenticpptmaster.engine.SvgFinalizer;
import domi.argenticpptmaster.engine.SvgQualityChecker;
import domi.argenticpptmaster.engine.SvgToPptxExporter;
import domi.argenticpptmaster.engine.TemplateFillEngine;
import domi.argenticpptmaster.engine.compare.DualRunSupport;
import domi.argenticpptmaster.engine.compare.SlideXmlComparator;
import domi.argenticpptmaster.engine.python.PythonBeautifyAnalysisEngine;
import domi.argenticpptmaster.engine.python.PythonImageGenerationGateway;
import domi.argenticpptmaster.engine.python.PythonNativeEnhanceEngine;
import domi.argenticpptmaster.engine.python.PythonNotesAudioGateway;
import domi.argenticpptmaster.engine.python.PythonPptxMarkdownReadback;
import domi.argenticpptmaster.engine.file.JavaBeautifyAnalysisEngine;
import domi.argenticpptmaster.engine.file.JavaNativeEnhanceEngine;
import domi.argenticpptmaster.engine.file.JavaProjectWorkspace;
import domi.argenticpptmaster.engine.file.JavaSpeakerNotesSplitter;
import domi.argenticpptmaster.engine.file.JavaTemplateFillEngine;
import domi.argenticpptmaster.engine.file.svgquality.JavaSvgQualityChecker;
import domi.argenticpptmaster.engine.python.PythonProjectWorkspace;
import domi.argenticpptmaster.engine.python.PythonSvgFinalizer;
import domi.argenticpptmaster.engine.python.PythonSvgQualityChecker;
import domi.argenticpptmaster.engine.python.PythonSvgToPptxExporter;
import domi.argenticpptmaster.engine.python.PythonTemplateFillEngine;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(PptEngineProperties.class)
public class PptEngineConfiguration {

    @Bean
    ProjectWorkspace projectWorkspace(PptMasterCommandExecutor commands) {
        return new RoutingProjectWorkspace(new JavaProjectWorkspace(), new PythonProjectWorkspace(commands));
    }

    @Bean
    BeautifyAnalysisEngine beautifyAnalysisEngine(PptMasterCommandExecutor commands, PptEngineProperties engineProperties) {
        // Java is the production authority. Optional dual-run keeps Python as secondary diagnostics only.
        return new RoutingBeautifyAnalysisEngine(
                new JavaBeautifyAnalysisEngine(), new PythonBeautifyAnalysisEngine(commands), engineProperties);
    }

    @Bean
    SpeakerNotesSplitter speakerNotesSplitter() {
        return new JavaSpeakerNotesSplitter();
    }

    @Bean
    SvgQualityChecker svgQualityChecker(PptMasterCommandExecutor commands) {
        // Java subset dual-runs; Python remains the production authority until parity.
        return new RoutingSvgQualityChecker(
                new JavaSvgQualityChecker(), new PythonSvgQualityChecker(commands));
    }

    @Bean
    SvgFinalizer svgFinalizer(PptMasterCommandExecutor commands) {
        return new PythonSvgFinalizer(commands);
    }

    @Bean
    SvgToPptxExporter svgToPptxExporter(PptMasterCommandExecutor commands) {
        return new PythonSvgToPptxExporter(commands);
    }

    @Bean
    TemplateFillEngine templateFillEngine(PptMasterCommandExecutor commands, PptEngineProperties engineProperties) {
        // Java is the production authority. Optional dual-run keeps Python as secondary diagnostics only.
        return new RoutingTemplateFillEngine(
                new JavaTemplateFillEngine(), new PythonTemplateFillEngine(commands), engineProperties);
    }

    @Bean
    NativeEnhanceEngine nativeEnhanceEngine(PptMasterCommandExecutor commands, PptEngineProperties engineProperties) {
        // Java is the production authority. Optional dual-run keeps Python as secondary diagnostics only.
        return new RoutingNativeEnhanceEngine(
                new JavaNativeEnhanceEngine(), new PythonNativeEnhanceEngine(commands), engineProperties);
    }

    @Bean
    @Primary
    @Qualifier("beautifyPptxMarkdownReadback")
    PptxMarkdownReadback beautifyPptxMarkdownReadback(PptMasterCommandExecutor commands) {
        return new PythonPptxMarkdownReadback(commands, PythonPptxMarkdownReadback.BEAUTIFY_SCRIPT);
    }

    @Bean
    @Qualifier("nativeEnhancePptxMarkdownReadback")
    PptxMarkdownReadback nativeEnhancePptxMarkdownReadback(PptMasterCommandExecutor commands) {
        return new PythonPptxMarkdownReadback(commands, PythonPptxMarkdownReadback.NATIVE_ENHANCE_SCRIPT);
    }

    @Bean
    ImageGenerationGateway imageGenerationGateway(PptMasterCommandExecutor commands) {
        return new PythonImageGenerationGateway(commands);
    }

    @Bean
    NotesAudioGateway notesAudioGateway(PptMasterCommandExecutor commands) {
        return new PythonNotesAudioGateway(commands);
    }

    @Bean
    SlideXmlComparator slideXmlComparator() {
        return new SlideXmlComparator();
    }

    @Bean
    DualRunSupport dualRunSupport(PptEngineProperties properties, SlideXmlComparator comparator) {
        return new DualRunSupport(properties, comparator);
    }

    @Bean
    PptEngineBundle pptEngineBundle(
            ProjectWorkspace projectWorkspace,
            BeautifyAnalysisEngine beautifyAnalysisEngine,
            SpeakerNotesSplitter speakerNotesSplitter,
            SvgQualityChecker svgQualityChecker,
            SvgFinalizer svgFinalizer,
            SvgToPptxExporter svgToPptxExporter,
            TemplateFillEngine templateFillEngine,
            NativeEnhanceEngine nativeEnhanceEngine,
            @Qualifier("beautifyPptxMarkdownReadback") PptxMarkdownReadback beautifyReadback,
            @Qualifier("nativeEnhancePptxMarkdownReadback") PptxMarkdownReadback nativeEnhanceReadback,
            ImageGenerationGateway imageGenerationGateway,
            NotesAudioGateway notesAudioGateway) {
        return new PptEngineBundle(
                projectWorkspace,
                beautifyAnalysisEngine,
                speakerNotesSplitter,
                svgQualityChecker,
                svgFinalizer,
                svgToPptxExporter,
                templateFillEngine,
                nativeEnhanceEngine,
                beautifyReadback,
                nativeEnhanceReadback,
                imageGenerationGateway,
                notesAudioGateway);
    }
}
