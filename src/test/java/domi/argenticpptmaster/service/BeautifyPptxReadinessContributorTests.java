package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

class BeautifyPptxReadinessContributorTests {

    @Test
    void reportsUpWhenClasspathMarkerPresent() {
        BeautifyPptxReadinessContributor contributor = new BeautifyPptxReadinessContributor();

        Health health = contributor.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsOnlyKeys("readiness_marker");
        @SuppressWarnings("unchecked")
        var marker = (java.util.Map<String, String>) health.getDetails().get("readiness_marker");
        assertThat(marker)
                .containsEntry("component", "readiness_marker")
                .containsEntry("status", "UP")
                .containsEntry("reasonCode", "OK")
                .containsEntry("expectedVersion", BeautifyPptxReadinessContributor.EXPECTED_UPSTREAM_VERSION);
        assertThat(health.toString()).doesNotContain("skills/ppt-master");
        assertThat(health.toString()).doesNotContain("PYTHON_");
    }

    @Test
    void reportsDownWithoutLeakingPathsWhenMarkerMissing() {
        BeautifyPptxReadinessContributor contributor =
                new BeautifyPptxReadinessContributor("beautify-pptx/missing-readiness-marker.txt");

        Health health = contributor.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        @SuppressWarnings("unchecked")
        var marker = (java.util.Map<String, String>) health.getDetails().get("readiness_marker");
        assertThat(marker.get("reasonCode")).isEqualTo("READINESS_MARKER_MISSING");
        assertThat(health.getDetails()).doesNotContainKeys("python", "beautify_identity", "project_manager");
        assertThat(health.toString()).doesNotContain("skills/ppt-master");
    }
}
