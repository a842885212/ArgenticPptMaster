package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PptArtifactRegistryTests {
    @TempDir
    Path tempDir;

    @Test
    void registersAndInvalidatesArtifactsByOutlineVersion() {
        PptArtifactRegistry registry = new PptArtifactRegistry();
        registry.register(tempDir, "svg_output/1.svg", 3);
        registry.register(tempDir, "notes/1.md", 3);

        assertThat(registry.isUsable(tempDir, "svg_output/1.svg", 3)).isTrue();
        registry.markStale(tempDir, 3);
        assertThat(registry.isUsable(tempDir, "svg_output/1.svg", 3)).isFalse();
        assertThat(registry.affected(tempDir, 3)).hasSize(2);
    }
}
