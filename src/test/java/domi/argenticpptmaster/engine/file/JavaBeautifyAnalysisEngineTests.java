package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.RoutingBeautifyAnalysisEngine;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JavaBeautifyAnalysisEngineTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @CsvSource({
        "pure-text-mvp.pptx, beautify-gold/pure, pure-text-mvp",
        "phase0-baseline.pptx, beautify-gold/phase0, phase0-baseline"
    })
    void identifyIntakeInventoryMatchGoldAndServiceContract(String fixture, String goldDir, String stem)
            throws Exception {
        Path pptx = copyFixture(fixture);
        JavaBeautifyAnalysisEngine engine = new JavaBeautifyAnalysisEngine();

        EngineResult identify = engine.identify(pptx, TIMEOUT);
        assertThat(identify.successful()).isTrue();
        JsonNode identity = MAPPER.readTree(identify.output());
        assertServiceIdentityContract(identity);
        assertIdentityParity(identity, loadGold(goldDir + "/" + stem + ".identity.json"));

        Path analysis = tempDir.resolve(stem + "-analysis");
        EngineResult intake = engine.intake(pptx, analysis, TIMEOUT);
        assertThat(intake.successful()).isTrue();

        Path identityPath = analysis.resolve(stem + ".identity.json");
        Path libraryPath = analysis.resolve(stem + ".slide_library.json");
        Path profilePath = analysis.resolve("source_profile.json");
        assertThat(identityPath).isRegularFile();
        assertThat(libraryPath).isRegularFile();
        assertThat(profilePath).isRegularFile();

        JsonNode writtenIdentity = MAPPER.readTree(identityPath.toFile());
        JsonNode library = MAPPER.readTree(libraryPath.toFile());
        JsonNode profile = MAPPER.readTree(profilePath.toFile());
        assertThat(writtenIdentity.path("slide_count").asInt())
                .isEqualTo(identity.path("slide_count").asInt());
        assertThat(library.path("slides").isArray()).isTrue();
        assertThat(library.path("slide_count").asInt()).isEqualTo(identity.path("slide_count").asInt());
        assertThat(library.path("slides").size()).isEqualTo(identity.path("slide_count").asInt());
        assertThat(profile.path("schema").asText()).isEqualTo("pptx_intake_index.v1");
        assertThat(profile.path("decks").isArray()).isTrue();
        assertThat(profile.path("decks")).isNotEmpty();

        Path inventoryPath = analysis.resolve("beautify_inventory.json");
        EngineResult inventoryResult = engine.inventory(libraryPath, Optional.empty(), inventoryPath, TIMEOUT);
        assertThat(inventoryResult.successful()).isTrue();
        JsonNode inventory = MAPPER.readTree(inventoryPath.toFile());
        assertServiceInventoryContract(identity, library, profile, inventory);
        assertInventoryParity(inventory, loadGold(goldDir + "/beautify_inventory.json"));
    }

    @Test
    void identifyPutsJsonOnStdoutForResolveFormat() throws Exception {
        Path pptx = copyFixture("pure-text-mvp.pptx");
        EngineResult result = new JavaBeautifyAnalysisEngine().identify(pptx, TIMEOUT);
        assertThat(result.successful()).isTrue();
        JsonNode identity = MAPPER.readTree(result.output());
        double aspect = identity.path("canvas").path("aspect").asDouble(0);
        assertThat(aspect).isGreaterThan(0);
        String format = Math.abs(aspect - 1.778D) <= Math.abs(aspect - 1.333D) ? "ppt169" : "ppt43";
        assertThat(format).isEqualTo("ppt43");
    }

    @Test
    void routingUsesJavaAuthorityByDefault() throws Exception {
        Path pptx = copyFixture("pure-text-mvp.pptx");
        CountingBeautifyEngine python = new CountingBeautifyEngine();
        EngineResult result =
                new RoutingBeautifyAnalysisEngine(new JavaBeautifyAnalysisEngine(), python)
                        .identify(pptx, TIMEOUT);
        assertThat(result.successful()).isTrue();
        assertThat(MAPPER.readTree(result.output()).path("slide_count").asInt()).isEqualTo(4);
        assertThat(python.calls).isZero();
    }

    private static void assertServiceIdentityContract(JsonNode identity) {
        assertThat(identity.path("slide_count").asInt()).isPositive();
        assertThat(identity.path("canvas").isObject()).isTrue();
        assertThat(identity.path("canvas").path("width_px").asInt()).isPositive();
        assertThat(identity.path("canvas").path("height_px").asInt()).isPositive();
        assertThat(identity.path("canvas").path("aspect").asDouble()).isPositive();
        assertThat(identity.path("theme").path("palette").isObject()).isTrue();
        assertThat(identity.path("observed").isObject()).isTrue();
    }

    private static void assertServiceInventoryContract(
            JsonNode identity, JsonNode library, JsonNode profile, JsonNode inventory) {
        int slideCount = inventory.path("slide_count").asInt(0);
        assertThat(identity.path("canvas").isObject()).isTrue();
        assertThat(library.path("slides").isArray()).isTrue();
        assertThat(profile.path("decks").isArray()).isTrue();
        assertThat(inventory.path("schema").asText()).isEqualTo("beautify_inventory.v1");
        assertThat(slideCount).isPositive();
        assertThat(identity.path("slide_count").asInt()).isEqualTo(slideCount);
        assertThat(library.path("slide_count").asInt()).isEqualTo(slideCount);
        assertThat(library.path("slides").size()).isEqualTo(slideCount);
        assertThat(inventory.path("slides").isArray()).isTrue();
        assertThat(inventory.path("slides").size()).isEqualTo(slideCount);
        assertThat(inventory.path("ignored").isMissingNode() || inventory.path("ignored").isArray()).isTrue();
        for (int index = 0; index < inventory.path("slides").size(); index++) {
            JsonNode slide = inventory.path("slides").get(index);
            assertThat(slide.path("slide_index").asInt()).isEqualTo(index + 1);
            assertThat(slide.path("text_blocks").isArray()).isTrue();
            for (JsonNode text : slide.path("text_blocks")) {
                assertThat(text.isObject()).isTrue();
                assertThat(text.path("text").isTextual()).isTrue();
            }
            assertThat(slide.path("ignored").isArray()).isTrue();
            assertThat(slide.path("ignored")).isEmpty();
            assertThat(slide.path("needs_confirmation").isArray()).isTrue();
            assertThat(slide.path("needs_confirmation")).isEmpty();
        }
    }

    private static void assertIdentityParity(JsonNode actual, JsonNode expected) {
        assertThat(actual.path("slide_count")).isEqualTo(expected.path("slide_count"));
        assertThat(actual.path("canvas")).isEqualTo(expected.path("canvas"));
        assertThat(actual.path("theme").path("palette")).isEqualTo(expected.path("theme").path("palette"));
        assertThat(actual.path("theme").path("fonts")).isEqualTo(expected.path("theme").path("fonts"));
        assertThat(actual.path("theme").path("sizes")).isEqualTo(expected.path("theme").path("sizes"));
        assertThat(stripSource(actual).path("observed")).isEqualTo(expected.path("observed"));
        assertThat(actual.path("layout_sizes_pt")).isEqualTo(expected.path("layout_sizes_pt"));
    }

    private static void assertInventoryParity(JsonNode actual, JsonNode expected) {
        assertThat(actual.path("schema")).isEqualTo(expected.path("schema"));
        assertThat(actual.path("slide_count")).isEqualTo(expected.path("slide_count"));
        assertThat(actual.path("canvas_px")).isEqualTo(expected.path("canvas_px"));
        assertThat(actual.path("slides").size()).isEqualTo(expected.path("slides").size());
        for (int i = 0; i < expected.path("slides").size(); i++) {
            JsonNode a = actual.path("slides").get(i);
            JsonNode e = expected.path("slides").get(i);
            assertThat(a.path("slide_index")).isEqualTo(e.path("slide_index"));
            assertThat(a.path("page_type")).isEqualTo(e.path("page_type"));
            assertThat(textLedger(a)).isEqualTo(textLedger(e));
            assertThat(tableCells(a)).isEqualTo(tableCells(e));
            assertThat(chartData(a)).isEqualTo(chartData(e));
            assertThat(a.path("diagrams").size()).isEqualTo(e.path("diagrams").size());
            assertThat(a.path("images")).isEqualTo(e.path("images"));
            assertThat(a.path("ignored")).isEqualTo(e.path("ignored"));
            assertThat(a.path("needs_confirmation")).isEqualTo(e.path("needs_confirmation"));
        }
    }

    private static JsonNode textLedger(JsonNode slide) {
        var arr = MAPPER.createArrayNode();
        for (JsonNode block : slide.path("text_blocks")) {
            ObjectNode row = MAPPER.createObjectNode();
            row.put("slot_id", block.path("slot_id").asText());
            row.put("role", block.path("role").asText());
            row.put("text", block.path("text").asText());
            row.put("paragraph_count", block.path("paragraph_count").asInt());
            arr.add(row);
        }
        return arr;
    }

    private static JsonNode tableCells(JsonNode slide) {
        var arr = MAPPER.createArrayNode();
        for (JsonNode table : slide.path("tables")) {
            ObjectNode row = MAPPER.createObjectNode();
            row.put("table_id", table.path("table_id").asText());
            row.set("cells", table.path("cells").deepCopy());
            arr.add(row);
        }
        return arr;
    }

    private static JsonNode chartData(JsonNode slide) {
        var arr = MAPPER.createArrayNode();
        for (JsonNode chart : slide.path("charts")) {
            ObjectNode row = MAPPER.createObjectNode();
            row.put("chart_id", chart.path("chart_id").asText());
            row.put("chart_type", chart.path("chart_type").asText());
            row.set("categories", chart.path("categories").deepCopy());
            row.set("series", chart.path("series").deepCopy());
            arr.add(row);
        }
        return arr;
    }

    private static JsonNode stripSource(JsonNode node) {
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        copy.remove("source");
        copy.remove("source_pptx");
        return copy;
    }

    private Path copyFixture(String name) throws Exception {
        Path target = tempDir.resolve(name);
        try (InputStream in = getClass().getResourceAsStream("/beautify-pptx/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            Files.copy(in, target);
        }
        return target;
    }

    private static JsonNode loadGold(String resource) throws Exception {
        try (InputStream in = JavaBeautifyAnalysisEngineTests.class.getResourceAsStream("/" + resource)) {
            assertThat(in).as("gold " + resource).isNotNull();
            return MAPPER.readTree(in);
        }
    }

    private static final class CountingBeautifyEngine implements domi.argenticpptmaster.engine.BeautifyAnalysisEngine {
        int calls;

        @Override
        public EngineResult identify(Path sourcePptx, Duration timeout) {
            calls++;
            return new EngineResult(0, "{}", false);
        }

        @Override
        public EngineResult intake(Path sourcePptx, Path analysisOutDir, Duration timeout) {
            calls++;
            return new EngineResult(0, "ok", false);
        }

        @Override
        public EngineResult inventory(
                Path slideLibrary, Optional<Path> imageManifest, Path inventoryOut, Duration timeout) {
            calls++;
            return new EngineResult(0, "ok", false);
        }
    }
}
