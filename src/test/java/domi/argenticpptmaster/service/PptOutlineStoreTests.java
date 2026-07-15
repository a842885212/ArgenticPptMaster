package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.domain.PptOutline;
import domi.argenticpptmaster.domain.SlideOutline;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PptOutlineStoreTests {
    @TempDir
    Path tempDir;

    @Test
    void writesAndReadsUtf8OutlineAtomically() throws Exception {
        PptOutlineStore store = new PptOutlineStore();
        PptOutline outline = new PptOutline(1, false,
                List.of(new SlideOutline(1, "市场背景", "增长窗口", List.of("趋势"), "增长曲线", null)));
        store.write(tempDir, outline);
        assertThat(Files.readString(store.path(tempDir))).contains("市场背景");
        assertThat(store.read(tempDir)).isEqualTo(outline);
    }
}
