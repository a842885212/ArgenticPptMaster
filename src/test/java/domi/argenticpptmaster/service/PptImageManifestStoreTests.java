package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.domain.PptOutline;
import domi.argenticpptmaster.domain.SlideOutline;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PptImageManifestStoreTests {
    @TempDir
    Path tempDir;

    @Test
    void derivesVersionBoundPendingItemsFromLockedOutline() {
        PptOutlineStore outlineStore = new PptOutlineStore();
        outlineStore.write(tempDir, new PptOutline(2, true, List.of(
                new SlideOutline(1, "封面", "结论", List.of("要点"), "插图", Map.of(
                        "purpose", "封面视觉", "prompt", "蓝色科技城市", "aspectRatio", "16:9")),
                new SlideOutline(2, "总结", "结论", List.of("要点"), "文字", null))));

        Map<String, Object> manifest = new PptImageManifestStore().writeFromLockedOutline(tempDir);

        assertThat(manifest).containsEntry("outlineVersion", 2);
        assertThat((List<?>) manifest.get("items")).singleElement().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("slideNo", 1).containsEntry("status", "Pending").containsEntry("filename", "slide-01-image.png");
    }

    @Test
    void rejectsUnlockedOutline() {
        PptOutlineStore outlineStore = new PptOutlineStore();
        outlineStore.write(tempDir, new PptOutline(1, false, List.of(
                new SlideOutline(1, "封面", "结论", List.of("要点"), "插图", null))));
        PptImageManifestStore store = new PptImageManifestStore();

        assertThatThrownBy(() -> store.writeFromLockedOutline(tempDir))
                .hasMessageContaining("locked outline");
    }

    @Test
    void rejectsManifestItemWhoseSourceVersionDoesNotMatchLockedOutline() throws Exception {
        PptOutlineStore outlineStore = new PptOutlineStore();
        outlineStore.write(tempDir, new PptOutline(3, true, List.of(
                new SlideOutline(1, "封面", "结论", List.of("要点"), "插图", null))));
        PptImageManifestStore store = new PptImageManifestStore();
        java.nio.file.Files.createDirectories(tempDir.resolve("images"));
        java.nio.file.Files.writeString(store.path(tempDir), """
                {"outlineVersion": 3, "items": [{"outlineVersion": 2, "slideNo": 1,
                  "filename": "slide-01-image.png", "prompt": "cover", "aspect_ratio": "16:9", "status": "Pending"}]}
                """);

        assertThatThrownBy(() -> store.readForOutlineVersion(tempDir, 3))
                .hasMessageContaining("image manifest is invalid")
                .hasRootCauseMessage("image manifest item outlineVersion does not match locked outline");
    }
}
