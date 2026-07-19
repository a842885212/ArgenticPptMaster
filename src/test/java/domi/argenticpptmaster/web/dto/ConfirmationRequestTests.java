package domi.argenticpptmaster.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.domain.PptConfirmationAction;
import domi.argenticpptmaster.domain.PptOutlineEditOperation;
import domi.argenticpptmaster.domain.SlideOutline;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfirmationRequestTests {

    @Test
    void keepsLegacyConstructorCompatible() {
        ConfirmationRequest request = new ConfirmationRequest("c-1", true, Map.of("answer", "yes"), "ok");

        assertThat(request.action()).isNull();
        assertThat(request.overallComment()).isNull();
        assertThat(request.slideEdits()).isEmpty();
    }

    @Test
    void carriesStructuredOutlineRevision() {
        ConfirmationRequest request = new ConfirmationRequest(
                "c-2",
                true,
                Map.of(),
                null,
                PptConfirmationAction.REQUEST_REVISION,
                "调整叙事",
                List.of(new PptSlideEditRequest(2, "替换视觉建议")));

        assertThat(request.action()).isEqualTo(PptConfirmationAction.REQUEST_REVISION);
        assertThat(request.slideEdits()).containsExactly(new PptSlideEditRequest(2, "替换视觉建议"));
    }

    @Test
    void carriesVersionedRevisionAndImpactToken() {
        ConfirmationRequest request = new ConfirmationRequest(
                "c-3", true, Map.of(), null, PptConfirmationAction.REQUEST_REVISION, "更新第 1 页", List.of(),
                2, List.of(new PptOutlineEditRequest(PptOutlineEditOperation.UPDATE, 1,
                        new SlideOutline(1, "新标题", "新结论", List.of("新要点"), "新视觉", null))), "impact-token");

        assertThat(request.outlineVersion()).isEqualTo(2);
        assertThat(request.outlineEdits()).hasSize(1);
        assertThat(request.revisionImpactToken()).isEqualTo("impact-token");
    }
}
