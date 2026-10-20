// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.engine.BeautifyAnalysisEngine;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.ooxml.beautify.BeautifyIdentityExtractor;
import domi.argenticpptmaster.engine.ooxml.beautify.BeautifyIntakeOps;
import domi.argenticpptmaster.engine.ooxml.beautify.BeautifyInventoryBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Java production beautify analysis (identity / intake / inventory).
 *
 * <p>Aligned with pin {@code cc535363} {@code beautify_identity.py}, {@code pptx_intake.py},
 * and {@code beautify_inventory.py} builder mode. {@link #identify} puts JSON on
 * {@link EngineResult#output()} for {@code resolveFormat}.</p>
 */
public final class JavaBeautifyAnalysisEngine implements BeautifyAnalysisEngine {

    public static final String HEADER = "[JAVA] beautify-analysis";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public EngineResult identify(Path sourcePptx, Duration timeout) {
        try {
            if (sourcePptx == null || !Files.isRegularFile(sourcePptx)) {
                return new EngineResult(1, HEADER + " source not found: " + sourcePptx, false);
            }
            ObjectNode identity = BeautifyIdentityExtractor.extractIdentity(sourcePptx);
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(identity);
            return new EngineResult(0, json, false);
        } catch (Exception ex) {
            return new EngineResult(1, HEADER + " identify failed: " + ex.getMessage(), false);
        }
    }

    @Override
    public EngineResult intake(Path sourcePptx, Path analysisOutDir, Duration timeout) {
        try {
            if (sourcePptx == null || !Files.isRegularFile(sourcePptx)) {
                return new EngineResult(1, HEADER + " source not found: " + sourcePptx, false);
            }
            Map<String, Path> outputs = BeautifyIntakeOps.runIntake(sourcePptx, analysisOutDir);
            StringBuilder message = new StringBuilder(HEADER)
                    .append(" PPTX intake -> ")
                    .append(analysisOutDir.toAbsolutePath().normalize());
            for (Map.Entry<String, Path> entry : outputs.entrySet()) {
                message.append('\n')
                        .append("  ")
                        .append(entry.getKey())
                        .append(": ")
                        .append(entry.getValue());
            }
            return new EngineResult(0, message.toString(), false);
        } catch (Exception ex) {
            return new EngineResult(1, HEADER + " intake failed: " + ex.getMessage(), false);
        }
    }

    @Override
    public EngineResult inventory(
            Path slideLibrary, Optional<Path> imageManifest, Path inventoryOut, Duration timeout) {
        try {
            if (slideLibrary == null || !Files.isRegularFile(slideLibrary)) {
                return new EngineResult(1, HEADER + " slide library not found: " + slideLibrary, false);
            }
            JsonNode library = MAPPER.readTree(slideLibrary.toFile());
            JsonNode manifest = null;
            if (imageManifest.isPresent()) {
                Path images = imageManifest.get();
                if (!Files.isRegularFile(images)) {
                    return new EngineResult(1, HEADER + " image manifest not found: " + images, false);
                }
                manifest = MAPPER.readTree(images.toFile());
            }
            ObjectNode inventory = BeautifyInventoryBuilder.buildInventory(library, manifest);
            if (inventoryOut != null) {
                BeautifyIntakeOps.writeJsonAtomic(inventoryOut, inventory);
                return new EngineResult(0, HEADER + " inventory written to: " + inventoryOut, false);
            }
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(inventory);
            return new EngineResult(0, json, false);
        } catch (Exception ex) {
            return new EngineResult(1, HEADER + " inventory failed: " + ex.getMessage(), false);
        }
    }
}
