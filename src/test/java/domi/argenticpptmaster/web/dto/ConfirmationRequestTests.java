package domi.argenticpptmaster.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.domain.PptConfirmationAction;
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
}
