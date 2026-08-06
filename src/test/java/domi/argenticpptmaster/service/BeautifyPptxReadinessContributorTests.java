package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.PptMasterProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Health;

class BeautifyPptxReadinessContributorTests {

    @TempDir
    Path tempDir;

    @Test
    void reportsUpWhenDependenciesExist() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path scripts = repo.resolve("skills/ppt-master/scripts");
        Files.createDirectories(scripts);
        for (String script : List.of(
                "beautify_identity", "beautify_inventory", "pptx_intake",
                "project_manager", "finalize_svg", "svg_to_pptx")) {
            Files.writeString(scripts.resolve(script + ".py"), "# stub\n");
        }
        Path python = tempDir.resolve("python");
        Files.writeString(python, "#!/bin/sh\n");
        python.toFile().setExecutable(true);

        BeautifyPptxReadinessContributor contributor = new BeautifyPptxReadinessContributor(
                new PptMasterProperties(repo, tempDir.resolve("workspace"), python.toString(),
                        java.time.Duration.ofMinutes(1), null, 0, 0, 0, 0, 0, null,
                        null, null, null, null));

        Health health = contributor.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsKeys(
                "python", "beautify_identity", "beautify_inventory", "pptx_intake",
                "project_manager", "finalize_svg", "svg_to_pptx", "readiness_marker");
        assertThat(health.getDetails().values()).allSatisfy(detail -> {
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<String, String>) detail;
            assertThat(map).containsKeys("component", "status", "expectedVersion", "reasonCode");
            assertThat(map.get("reasonCode")).isEqualTo("OK");
            assertThat(map.get("expectedVersion"))
                    .isEqualTo(BeautifyPptxReadinessContributor.EXPECTED_UPSTREAM_VERSION);
        });
        assertThat(health.toString()).doesNotContain(tempDir.toString());
    }

    @Test
    void reportsDownWithoutLeakingPathsWhenScriptsMissing() {
        BeautifyPptxReadinessContributor contributor = new BeautifyPptxReadinessContributor(
                new PptMasterProperties(
                        tempDir.resolve("missing-repo"),
                        tempDir.resolve("workspace"),
                        tempDir.resolve("missing-python").toString(),
                        java.time.Duration.ofMinutes(1),
                        null, 0, 0, 0, 0, 0, null, null, null, null, null));

        Health health = contributor.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        @SuppressWarnings("unchecked")
        var python = (java.util.Map<String, String>) health.getDetails().get("python");
        assertThat(python.get("reasonCode")).isIn("PYTHON_MISSING", "PYTHON_NOT_EXECUTABLE");
        @SuppressWarnings("unchecked")
        var identity = (java.util.Map<String, String>) health.getDetails().get("beautify_identity");
        assertThat(identity.get("reasonCode")).isEqualTo("SCRIPT_MISSING");
        assertThat(health.toString()).doesNotContain(tempDir.toString());
        assertThat(health.toString()).doesNotContain("skills/ppt-master");
    }
}
