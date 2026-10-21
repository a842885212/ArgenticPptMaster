// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import com.fasterxml.jackson.databind.JsonNode;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.NativeEnhanceEngine;
import domi.argenticpptmaster.engine.ooxml.nativeenhance.EnhancementPlanSupport;
import domi.argenticpptmaster.engine.ooxml.nativeenhance.NativeEnhanceOps;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Java production authority for native PPTX enhancement.
 *
 * <p>Delegates init / validate / apply to {@link NativeEnhanceOps} (notes, OOXML audio
 * embedding, timings, transitions). TTS generation stays on {@code NotesAudioGateway};
 * audio files are assumed to already exist under the project {@code audio/} directory.</p>
 */
public final class JavaNativeEnhanceEngine implements NativeEnhanceEngine {

    /** Legacy dual-run skip marker; production paths no longer emit this prefix. */
    public static final String SKIP_PREFIX = "[JAVA-SUBSET] skip:";

    @Override
    public EngineResult init(
            Path sourcePptx, String name, Path projectDir, Path projectsRoot, Duration timeout) {
        return NativeEnhanceOps.init(sourcePptx, name, projectDir, projectsRoot);
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        return NativeEnhanceOps.validate(project);
    }

    @Override
    public EngineResult apply(List<String> arguments, Duration timeout) {
        return NativeEnhanceOps.apply(arguments);
    }

    /** Retained for dual-run helpers / tests that still inspect module sets. */
    public static boolean isNotesOnlyEligible(Path project) {
        try {
            Set<String> modules = EnhancementPlanSupport.enabledModules(EnhancementPlanSupport.loadPlan(project));
            return modules.equals(Set.of("notes"));
        } catch (Exception ex) {
            return false;
        }
    }

    public static boolean isSkip(EngineResult result) {
        return result != null
                && result.output() != null
                && result.output().startsWith(SKIP_PREFIX);
    }

    static Set<String> enabledModules(JsonNode plan) {
        return EnhancementPlanSupport.enabledModules(plan);
    }
}
