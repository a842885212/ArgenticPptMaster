package domi.argenticpptmaster.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.domain.PptJob;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PptJobResponseTests {

    @Test
    void hidesDownloadInfoUntilJobCompleted() {
        PptJob job = new PptJob(
                UUID.randomUUID(),
                "demo",
                "ppt169",
                "make a deck",
                Path.of("var/ppt-master/jobs/demo"));

        job.complete(Path.of("var/ppt-master/jobs/demo/exports/demo.pptx"));
        job.requireConfirmation("confirm-again", java.util.Map.of("stage", "manual"));

        PptJobResponse response = PptJobResponse.from(job);

        assertThat(response.artifactReady()).isFalse();
        assertThat(response.downloadUrl()).isNull();
    }
}
