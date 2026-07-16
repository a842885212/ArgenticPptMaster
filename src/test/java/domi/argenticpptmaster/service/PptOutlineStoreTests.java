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
        assertThat(Files.exists(store.historyPath(tempDir, 1))).isTrue();
        assertThat(Files.exists(store.metadataPath(tempDir))).isTrue();
    }

    @Test
    void keepsPreviousVersionSnapshotWhenWritingRevision() throws Exception {
        PptOutlineStore store = new PptOutlineStore();
        PptOutline first = new PptOutline(1, false,
                List.of(new SlideOutline(1, "旧标题", "结论", List.of("要点"), "图表", null)));
        PptOutline second = new PptOutline(2, false,
                List.of(new SlideOutline(1, "新标题", "结论", List.of("要点"), "图表", null)));

        store.write(tempDir, first);
        store.write(tempDir, second);

        assertThat(store.read(tempDir)).isEqualTo(second);
        assertThat(Files.readString(store.historyPath(tempDir, 1))).contains("旧标题");
        assertThat(Files.readString(store.historyPath(tempDir, 2))).contains("新标题");
    }
}
