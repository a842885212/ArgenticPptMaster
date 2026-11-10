package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.exception.PptJobStateException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateFillPlanValidatorTests {

    @TempDir
    Path tempDir;

    private final TemplateFillPlanValidator validator = new TemplateFillPlanValidator();

    @Test
    void acceptsValidDraftAgainstSlideLibrary() throws Exception {
        Path slideLibrary = copyResource("template.slide_library.json");
        String plan = Files.readString(Path.of(getClass().getResource("/template-fill/fill-plan-draft-valid.json").toURI()));

        var parsed = validator.parseAndValidateDraft(plan, slideLibrary);

        assertThat(parsed.get("status").asText()).isEqualTo("draft");
        assertThat(parsed.get("slides")).hasSize(2);
    }

    @Test
    void rejectsInvalidTemplateReferences() throws Exception {
        Path slideLibrary = copyResource("template.slide_library.json");
        String plan = Files.readString(Path.of(getClass().getResource("/template-fill/fill-plan-draft-invalid-refs.json").toURI()));

        assertThatThrownBy(() -> validator.parseAndValidateDraft(plan, slideLibrary))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("unknown template slide");
    }

    @Test
    void rejectsConfirmedStatusFromAgent() throws Exception {
        Path slideLibrary = copyResource("template.slide_library.json");

        assertThatThrownBy(() -> validator.parseAndValidateDraft(
                "{\"status\":\"confirmed\",\"slides\":[{\"outputOrder\":1,\"templateSlideIndex\":1}]}", slideLibrary))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("draft");
    }

    @Test
    void rejectsReplacementThatTargetsTableShape() throws Exception {
        Path slideLibrary = tempDir.resolve("conflict-library.json");
        Files.writeString(
                slideLibrary,
                """
                {
                  "schema": "template_fill_pptx_library.v1",
                  "slide_count": 1,
                  "slides": [
                    {
                      "slide_index": 1,
                      "page_type": "content_candidate",
                      "slots": [
                        {
                          "slot_id": "s01_sh3",
                          "role": "body_candidate",
                          "text": "A",
                          "paragraph_count": 1,
                          "geometry": { "x": 0, "y": 0, "width": 100, "height": 100 },
                          "text_metrics": { "font_size_px": 16.0, "paragraph_count": 1 }
                        }
                      ],
                      "tables": [
                        { "table_id": "s01_tbl3", "row_count": 1, "column_count": 1 }
                      ],
                      "charts": []
                    }
                  ]
                }
                """);

        String plan =
                """
                {
                  "schema": "template_fill_pptx_plan.v1",
                  "status": "draft",
                  "slides": [
                    {
                      "source_slide": 1,
                      "replacements": [{"slot_id": "s01_sh3", "text": "结论写进表格"}],
                      "table_edits": [],
                      "chart_edits": []
                    }
                  ]
                }
                """;

        assertThatThrownBy(() -> validator.parseAndValidateDraft(plan, slideLibrary))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("targets table object")
                .hasMessageContaining("s01_tbl3");
    }

    @Test
    void acceptsTableEditsWithoutReplacementOnSameTable() throws Exception {
        Path slideLibrary = copyResource("template.slide_library.json");
        String plan =
                """
                {
                  "schema": "template_fill_pptx_plan.v1",
                  "status": "draft",
                  "slides": [
                    {
                      "source_slide": 1,
                      "replacements": [{"slot_id": "s01_sh1", "text": "封面"}],
                      "table_edits": [],
                      "chart_edits": []
                    },
                    {
                      "source_slide": 2,
                      "replacements": [
                        {"slot_id": "s02_sh1", "text": "标题"},
                        {"slot_id": "s02_sh2", "text": "正文"}
                      ],
                      "table_edits": [
                        {
                          "table_id": "s02_tbl10",
                          "cells": [{"row": 0, "col": 0, "text": "摘要"}]
                        }
                      ],
                      "chart_edits": []
                    }
                  ]
                }
                """;

        var parsed = validator.parseAndValidateDraft(plan, slideLibrary);

        assertThat(parsed.path("slides").get(1).path("table_edits").get(0).path("table_id").asText())
                .isEqualTo("s02_tbl10");
    }

    private Path copyResource(String name) throws IOException {
        Path target = tempDir.resolve(name);
        Files.copy(getClass().getResourceAsStream("/template-fill/" + name), target);
        return target;
    }
}
