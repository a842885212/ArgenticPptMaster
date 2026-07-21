package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins stage-4 upstream JSON contracts without invoking Python.
 * Real CLI smoke coverage belongs to integration environments with ppt-master installed.
 */
class TemplateFillUpstreamContractTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void stage4LibraryExposesStableIdsAndNativeCapabilities() throws Exception {
        JsonNode library = resource("stage4.slide_library.json");

        assertThat(library.path("schema").asText()).isEqualTo("template_fill_pptx_library.v1");
        assertThat(library.path("slide_count").asInt()).isEqualTo(4);
        assertThat(library.path("plan_contract").path("schema").asText())
                .isEqualTo("template_fill_pptx_plan.v1");

        Set<String> slotIds = new LinkedHashSet<>();
        Set<String> tableIds = new LinkedHashSet<>();
        Set<String> chartIds = new LinkedHashSet<>();
        for (JsonNode slide : library.path("slides")) {
            assertThat(slide.path("slide_index").asInt()).isPositive();
            assertThat(slide.path("page_type").asText()).isNotBlank();
            for (JsonNode slot : slide.path("slots")) {
                String id = slot.path("slot_id").asText();
                assertThat(id).isNotBlank();
                assertThat(slotIds.add(id)).isTrue();
                assertThat(slot.path("text_metrics").path("font_size_px").isNumber()).isTrue();
            }
            for (JsonNode table : slide.path("tables")) {
                String id = table.path("table_id").asText();
                assertThat(id).isNotBlank();
                assertThat(tableIds.add(id)).isTrue();
                assertThat(table.path("row_count").asInt()).isPositive();
                assertThat(table.path("column_count").asInt()).isPositive();
            }
            for (JsonNode chart : slide.path("charts")) {
                String id = chart.path("chart_id").asText();
                assertThat(id).isNotBlank();
                assertThat(chartIds.add(id)).isTrue();
                assertThat(chart.path("categories").isArray()).isTrue();
                assertThat(chart.path("series").isArray()).isTrue();
            }
        }
        assertThat(slotIds).isNotEmpty();
        assertThat(tableIds).contains("s02_tbl1");
        assertThat(chartIds).contains("s03_ch1");
    }

    @Test
    void stage4PlanMatchesUpstreamApplyContract() throws Exception {
        JsonNode plan = resource("fill-plan-stage4-draft.json");
        JsonNode library = resource("stage4.slide_library.json");

        assertThat(plan.path("schema").asText()).isEqualTo("template_fill_pptx_plan.v1");
        assertThat(plan.path("status").asText()).isEqualTo("draft");
        assertThat(plan.path("slides")).isNotEmpty();

        Set<Integer> librarySlides = new LinkedHashSet<>();
        Set<String> librarySlots = new LinkedHashSet<>();
        Set<String> libraryTables = new LinkedHashSet<>();
        Set<String> libraryCharts = new LinkedHashSet<>();
        for (JsonNode slide : library.path("slides")) {
            librarySlides.add(slide.path("slide_index").asInt());
            slide.path("slots").forEach(node -> librarySlots.add(node.path("slot_id").asText()));
            slide.path("tables").forEach(node -> libraryTables.add(node.path("table_id").asText()));
            slide.path("charts").forEach(node -> libraryCharts.add(node.path("chart_id").asText()));
        }

        for (JsonNode slide : plan.path("slides")) {
            int sourceSlide = slide.path("source_slide").asInt();
            assertThat(librarySlides).contains(sourceSlide);
            assertThat(slide.path("transition").asText()).isNotBlank();
            assertThat(slide.has("notes") || slide.has("speaker_notes")).isTrue();
            for (JsonNode replacement : slide.path("replacements")) {
                assertThat(librarySlots).contains(replacement.path("slot_id").asText());
                assertThat(replacement.has("font_size_px")).isFalse();
            }
            for (JsonNode tableEdit : slide.path("table_edits")) {
                assertThat(libraryTables).contains(tableEdit.path("table_id").asText());
            }
            for (JsonNode chartEdit : slide.path("chart_edits")) {
                assertThat(libraryCharts).contains(chartEdit.path("chart_id").asText());
                assertThat(chartEdit.path("categories").isArray()).isTrue();
                assertThat(chartEdit.path("series").isArray()).isTrue();
            }
        }
    }

    @Test
    void supportMatrixDocumentsFontAdjustAsUnsupported() throws Exception {
        String matrix = Files.readString(Path.of(getClass()
                .getResource("/template-fill/stage4-support-matrix.md").toURI()));
        assertThat(matrix).contains("not supported by apply");
        assertThat(matrix).contains("template_fill_pptx_plan.v1");
        assertThat(matrix).contains("notes");
    }

    @Test
    void invalidRefsFixturePointsAtMissingStableIds() throws Exception {
        JsonNode plan = resource("fill-plan-stage4-invalid-refs.json");
        Iterator<JsonNode> slides = plan.path("slides").elements();
        assertThat(slides.hasNext()).isTrue();
        JsonNode slide = slides.next();
        assertThat(slide.path("source_slide").asInt()).isEqualTo(99);
        assertThat(slide.path("replacements").get(0).path("slot_id").asText()).isEqualTo("missing_slot");
    }

    private JsonNode resource(String name) throws Exception {
        return mapper.readTree(getClass().getResourceAsStream("/template-fill/" + name));
    }
}
