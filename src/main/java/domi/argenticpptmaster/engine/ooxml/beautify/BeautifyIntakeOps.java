// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.beautify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.engine.ooxml.templatefill.TfAnalyzer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Port of {@code pptx_intake}: identity + slide_library + multi-deck {@code source_profile.json}.
 */
public final class BeautifyIntakeOps {

    public static final String SOURCE_INDEX_NAME = "source_profile.json";
    public static final String INDEX_SCHEMA = "pptx_intake_index.v1";
    public static final String PROFILE_SCHEMA = "pptx_intake_profile.v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BeautifyIntakeOps() {}

    public static Map<String, Path> runIntake(Path pptxPath, Path outputDir) throws Exception {
        Files.createDirectories(outputDir);
        String stem = stem(pptxPath);
        ObjectNode identity = BeautifyIdentityExtractor.extractIdentity(pptxPath);
        ObjectNode slideLibrary = TfAnalyzer.analyzePptx(pptxPath);
        ObjectNode digest = buildSourceProfile(pptxPath, identity, slideLibrary, stem);

        Path identityPath = outputDir.resolve(stem + ".identity.json");
        Path slideLibraryPath = outputDir.resolve(stem + ".slide_library.json");
        Path lockPath = outputDir.resolve(SOURCE_INDEX_NAME + ".lock");
        Path profilePath;
        try (FileChannel channel = FileChannel.open(
                        lockPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            writeJsonAtomic(identityPath, identity);
            writeJsonAtomic(slideLibraryPath, slideLibrary);
            profilePath = upsertSourceIndexUnlocked(outputDir, digest);
        }
        Map<String, Path> outputs = new LinkedHashMap<>();
        outputs.put("identity", identityPath);
        outputs.put("slide_library", slideLibraryPath);
        outputs.put("source_profile", profilePath);
        return outputs;
    }

    public static ObjectNode buildSourceProfile(
            Path pptxPath, JsonNode identity, JsonNode slideLibrary, String stem) {
        String resolvedStem = stem != null ? stem : stem(pptxPath);
        ObjectNode profile = MAPPER.createObjectNode();
        profile.put("schema", PROFILE_SCHEMA);
        profile.put("stem", resolvedStem);
        profile.put("source_pptx", pptxPath.toAbsolutePath().normalize().toString());
        profile.put(
                "slide_count",
                slideLibrary.path("slide_count").asInt(identity.path("slide_count").asInt(0)));

        ObjectNode usage = MAPPER.createObjectNode();
        usage.put(
                "standard_generation",
                "Use identity and slide-library fields as source facts and recommendation "
                        + "candidates only; do not preserve original page count, order, or coordinates "
                        + "unless the user selected the beautify profile or Fill Native PPTX route.");
        usage.put(
                "beautify",
                "Promote source text, page order, page count, colors, fonts, and font sizes "
                        + "into locked constraints after user confirmation.");
        usage.put(
                "template_fill",
                "Use slide slots, tables, charts, diagrams, and geometry as the native PPTX "
                        + "fill contract; diagrams are inventory-only and remain unchanged.");
        profile.set("usage_contract", usage);

        ObjectNode artifacts = MAPPER.createObjectNode();
        artifacts.put("identity", resolvedStem + ".identity.json");
        artifacts.put("slide_library", resolvedStem + ".slide_library.json");
        profile.set("artifacts", artifacts);

        profile.set("canvas", identity.path("canvas").deepCopy());

        ObjectNode identityDigest = MAPPER.createObjectNode();
        JsonNode theme = identity.path("theme");
        JsonNode observed = identity.path("observed");
        identityDigest.set("theme_palette", theme.path("palette").deepCopy());
        identityDigest.set("theme_fonts", theme.path("fonts").deepCopy());
        identityDigest.set("theme_sizes", theme.path("sizes").deepCopy());
        identityDigest.set("observed_colors", observed.path("colors").deepCopy());
        identityDigest.set("observed_fonts", observed.path("fonts").deepCopy());
        identityDigest.set("observed_sizes_pt", observed.path("sizes_pt").deepCopy());
        identityDigest.set("layout_sizes_pt", identity.path("layout_sizes_pt").deepCopy());
        profile.set("identity", identityDigest);

        ObjectNode structure = MAPPER.createObjectNode();
        structure.set("canvas_px", slideLibrary.path("canvas_px").deepCopy());
        ArrayNode pageTypes = MAPPER.createArrayNode();
        for (JsonNode slide : iterable(slideLibrary.path("slides"))) {
            ObjectNode page = MAPPER.createObjectNode();
            page.put("slide_index", slide.path("slide_index").asInt());
            putText(page, "page_type", slide.path("page_type"));
            page.put("slot_count", slide.path("slots").isArray() ? slide.path("slots").size() : 0);
            page.put("diagram_count", slide.path("diagrams").isArray() ? slide.path("diagrams").size() : 0);
            pageTypes.add(page);
        }
        structure.set("page_types", pageTypes);
        profile.set("structure", structure);

        profile.set("tables", tableSummary(slideLibrary));
        profile.set("charts", chartSummary(slideLibrary));
        profile.set("diagrams", diagramSummary(slideLibrary));
        return profile;
    }

    public static Path upsertSourceIndex(Path outputDir, ObjectNode digest) throws Exception {
        Files.createDirectories(outputDir);
        Path lockPath = outputDir.resolve(SOURCE_INDEX_NAME + ".lock");
        try (FileChannel channel = FileChannel.open(
                        lockPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            return upsertSourceIndexUnlocked(outputDir, digest);
        }
    }

    private static Path upsertSourceIndexUnlocked(Path outputDir, ObjectNode digest) throws Exception {
        Path indexPath = outputDir.resolve(SOURCE_INDEX_NAME);
        ObjectNode index = loadSourceIndex(indexPath);
        String stem = digest.path("stem").asText();
        ArrayNode decks = MAPPER.createArrayNode();
        for (JsonNode deck : iterable(index.path("decks"))) {
            if (!stem.equals(deck.path("stem").asText())) {
                decks.add(deck.deepCopy());
            }
        }
        decks.add(digest);
        List<JsonNode> sorted = new ArrayList<>();
        decks.forEach(sorted::add);
        sorted.sort(Comparator.comparing(d -> d.path("stem").asText("")));
        ArrayNode ordered = MAPPER.createArrayNode();
        sorted.forEach(ordered::add);

        ObjectNode out = MAPPER.createObjectNode();
        out.put("schema", INDEX_SCHEMA);
        out.put("deck_count", ordered.size());
        out.set("decks", ordered);
        writeJsonAtomic(indexPath, out);
        return indexPath;
    }

    private static ObjectNode loadSourceIndex(Path indexPath) throws Exception {
        if (!Files.exists(indexPath)) {
            return MAPPER.createObjectNode();
        }
        if (!Files.isRegularFile(indexPath)) {
            throw new IllegalStateException("Source index is not a file: " + indexPath);
        }
        JsonNode loaded;
        try {
            loaded = MAPPER.readTree(Files.readString(indexPath, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Source index contains invalid JSON and was left unchanged: " + indexPath, ex);
        }
        if (!loaded.isObject() || !loaded.path("decks").isArray()) {
            throw new IllegalStateException(
                    "Source index must be a JSON object with a decks array and was left unchanged: "
                            + indexPath);
        }
        for (int i = 0; i < loaded.path("decks").size(); i++) {
            if (!loaded.path("decks").get(i).isObject()) {
                throw new IllegalStateException(
                        "Source index decks[" + i + "] must be an object and was left unchanged: "
                                + indexPath);
            }
        }
        return (ObjectNode) loaded;
    }

    private static ObjectNode tableSummary(JsonNode slideLibrary) {
        ArrayNode tables = MAPPER.createArrayNode();
        for (JsonNode slide : iterable(slideLibrary.path("slides"))) {
            for (JsonNode table : iterable(slide.path("tables"))) {
                ObjectNode item = MAPPER.createObjectNode();
                item.put("slide_index", slide.path("slide_index").asInt());
                putText(item, "table_id", table.path("table_id"));
                item.put("row_count", table.path("row_count").asInt(0));
                item.put("column_count", table.path("column_count").asInt(0));
                tables.add(item);
            }
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("table_count", tables.size());
        out.set("tables", tables);
        return out;
    }

    private static ObjectNode chartSummary(JsonNode slideLibrary) {
        ArrayNode charts = MAPPER.createArrayNode();
        int totalSeries = 0;
        int multiPlot = 0;
        for (JsonNode slide : iterable(slideLibrary.path("slides"))) {
            for (JsonNode chart : iterable(slide.path("charts"))) {
                int seriesCount = chart.path("series_count").asInt(0);
                JsonNode plotTypes = chart.path("plot_types");
                if (plotTypes.isArray() && plotTypes.size() > 1) {
                    multiPlot++;
                }
                totalSeries += seriesCount;
                ObjectNode item = MAPPER.createObjectNode();
                item.put("slide_index", slide.path("slide_index").asInt());
                putText(item, "chart_id", chart.path("chart_id"));
                putText(item, "chart_type", chart.path("chart_type"));
                item.set("plot_types", plotTypes.deepCopy());
                item.put("category_count", chart.path("category_count").asInt(0));
                item.put("series_count", seriesCount);
                ArrayNode names = MAPPER.createArrayNode();
                for (JsonNode series : iterable(chart.path("series"))) {
                    if (series.path("name").isTextual() && !series.path("name").asText().isBlank()) {
                        names.add(series.path("name").asText());
                    }
                }
                item.set("series_names", names);
                charts.add(item);
            }
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("chart_count", charts.size());
        out.put("series_count", totalSeries);
        out.put("multi_plot_chart_count", multiPlot);
        out.set("charts", charts);
        return out;
    }

    private static ObjectNode diagramSummary(JsonNode slideLibrary) {
        ArrayNode diagrams = MAPPER.createArrayNode();
        int textItemCount = 0;
        int unreadable = 0;
        int warningCount = 0;
        TreeSet<Integer> slidesWithDiagrams = new TreeSet<>();
        for (JsonNode slide : iterable(slideLibrary.path("slides"))) {
            int slideIndex = slide.path("slide_index").asInt(Integer.MIN_VALUE);
            for (JsonNode diagram : iterable(slide.path("diagrams"))) {
                int nodeCount = diagram.path("node_count").asInt(0);
                int textCount = diagram.path("text_count").asInt(0);
                textItemCount += textCount;
                if (!diagram.path("text_extracted").asBoolean(false)) {
                    unreadable++;
                }
                if (!"ok".equals(diagram.path("status").asText())
                        || (diagram.path("warnings").isArray() && !diagram.path("warnings").isEmpty())) {
                    warningCount++;
                }
                if (slideIndex != Integer.MIN_VALUE) {
                    slidesWithDiagrams.add(slideIndex);
                }
                ObjectNode item = MAPPER.createObjectNode();
                if (slideIndex != Integer.MIN_VALUE) {
                    item.put("slide_index", slideIndex);
                } else {
                    item.putNull("slide_index");
                }
                putText(item, "diagram_id", diagram.path("diagram_id"));
                item.set("layout", diagram.path("layout").deepCopy());
                item.put("node_count", nodeCount);
                item.put("text_count", textCount);
                item.put("connection_count", diagram.path("connection_count").asInt(0));
                item.put("max_depth", diagram.path("max_depth").asInt(0));
                item.put("text_extracted", diagram.path("text_extracted").asBoolean(false));
                item.put("has_persisted_drawing", diagram.path("has_persisted_drawing").asBoolean(false));
                putText(item, "status", diagram.path("status"));
                item.set("warnings", diagram.path("warnings").deepCopy());
                diagrams.add(item);
            }
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("diagram_count", diagrams.size());
        out.put("text_item_count", textItemCount);
        out.put("unreadable_count", unreadable);
        out.put("warning_count", warningCount);
        ArrayNode slidesArr = MAPPER.createArrayNode();
        slidesWithDiagrams.forEach(slidesArr::add);
        out.set("slides_with_diagrams", slidesArr);
        out.set("diagrams", diagrams);
        return out;
    }

    public static void writeJsonAtomic(Path path, JsonNode payload) throws Exception {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "." + path.getFileName() + ".", ".tmp");
        try {
            Files.writeString(
                    temp,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload) + "\n",
                    StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            Files.deleteIfExists(temp);
            throw ex;
        }
    }

    private static String stem(Path pptxPath) {
        String name = pptxPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void putText(ObjectNode target, String field, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || !value.isTextual()) {
            target.putNull(field);
        } else {
            target.put(field, value.asText());
        }
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        return node;
    }
}
