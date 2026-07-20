package domi.argenticpptmaster.util;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.domain.PptJobNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PptConfirmationStageResolverTemplateFillTests {

    @Test
    void resolvesTemplateFillPlanStageToFillPlanConfirmed() {
        Map<String, Object> payload = Map.of(
                "stage", "template_fill_plan",
                "contextData", Map.of(
                        "type", "template_fill_plan",
                        "version", 1,
                        "digest", "abc",
                        "pages", List.of(Map.of(
                                "outputOrder", 1,
                                "templateSlideIndex", 2,
                                "layoutNote", "body",
                                "slotMappings", List.of(Map.of(
                                        "slotId", "s02_sh1",
                                        "sourceRef", "content:content.md",
                                        "preview", "摘要预览")),
                                "omittedContent", List.of("附录"),
                                "capacityRisks", List.of("容量风险"),
                                "splitSuggestions", List.of("拆页"),
                                "tableChartHandling", List.of(Map.of(
                                        "targetId", "s02_tbl1",
                                        "strategy", "摘要")),
                                "acceptedWarnings", List.of("warn")))));

        assertThat(PptConfirmationStageResolver.resolveConfirmedNode(payload))
                .isEqualTo(PptJobNode.FILL_PLAN_CONFIRMED);
    }
}
