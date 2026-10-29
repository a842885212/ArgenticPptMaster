// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Embedded PPTX preset type / adjustment guide catalog for quality validation. */
final class SvgQualityPresetCatalog {

    private static final Set<String> SHAPE_TYPES = loadLines("svgquality/pptx-preset-shape-types.txt");
    private static final Set<String> CONNECTOR_TYPES = loadLines("svgquality/pptx-connector-preset-types.txt");
    private static final Map<String, Set<String>> GUIDES = loadGuides();

    private SvgQualityPresetCatalog() {}

    static boolean isKnownPreset(String prst) {
        return prst != null && SHAPE_TYPES.contains(prst);
    }

    static boolean isConnectorPreset(String prst) {
        return prst != null && CONNECTOR_TYPES.contains(prst);
    }

    static Set<String> guideNames(String prst) {
        return GUIDES.getOrDefault(prst, Set.of());
    }

    private static Set<String> loadLines(String resource) {
        try (InputStream in = SvgQualityPresetCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + resource);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> out = new LinkedHashSet<>();
            for (String line : text.split("\\R")) {
                String value = line.strip();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
            return Collections.unmodifiableSet(out);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to load " + resource, ex);
        }
    }

    private static Map<String, Set<String>> loadGuides() {
        String resource = "svgquality/pptx-preset-guides.json";
        try (InputStream in = SvgQualityPresetCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + resource);
            }
            Map<String, List<String>> raw =
                    new ObjectMapper().readValue(in, new TypeReference<Map<String, List<String>>>() {});
            Map<String, Set<String>> out = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
                out.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            return Collections.unmodifiableMap(out);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to load " + resource, ex);
        }
    }
}
