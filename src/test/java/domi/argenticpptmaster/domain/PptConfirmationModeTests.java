package domi.argenticpptmaster.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PptConfirmationModeTests {

    @Test
    void fromDefaultsBlankToInteractive() {
        assertThat(PptConfirmationMode.from(null)).isEqualTo(PptConfirmationMode.INTERACTIVE);
        assertThat(PptConfirmationMode.from("")).isEqualTo(PptConfirmationMode.INTERACTIVE);
        assertThat(PptConfirmationMode.from("  ")).isEqualTo(PptConfirmationMode.INTERACTIVE);
    }

    @Test
    void fromParsesKnownAliases() {
        assertThat(PptConfirmationMode.from("auto")).isEqualTo(PptConfirmationMode.AUTO);
        assertThat(PptConfirmationMode.from("AUTO")).isEqualTo(PptConfirmationMode.AUTO);
        assertThat(PptConfirmationMode.from("interactive")).isEqualTo(PptConfirmationMode.INTERACTIVE);
        assertThat(PptConfirmationMode.from("hitl")).isEqualTo(PptConfirmationMode.INTERACTIVE);
    }

    @Test
    void fromRejectsUnknownValue() {
        assertThatThrownBy(() -> PptConfirmationMode.from("semi-auto"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported confirmation mode");
    }

    @Test
    void pptJobDefaultsToInteractiveAndKeepsExplicitAuto() {
        PptJob interactive = new PptJob(
                UUID.randomUUID(), "demo", "ppt169", "x", PptWorkflowMode.BASIC, Path.of("var/demo"));
        assertThat(interactive.confirmationMode()).isEqualTo(PptConfirmationMode.INTERACTIVE);

        PptJob auto = new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "x",
                PptWorkflowMode.BASIC,
                PptConfirmationMode.AUTO,
                Path.of("var/demo-auto"));
        assertThat(auto.confirmationMode()).isEqualTo(PptConfirmationMode.AUTO);
        assertThat(auto.autoImageRetryRounds()).isZero();
        assertThat(auto.recordAutoImageRetry()).isEqualTo(1);
        assertThat(auto.autoImageRetryRounds()).isEqualTo(1);
    }
}
