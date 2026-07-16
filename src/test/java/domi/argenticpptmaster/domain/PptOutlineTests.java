package domi.argenticpptmaster.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PptOutlineTests {
    private SlideOutline slide(String title) {
        return new SlideOutline(1, title, "结论", List.of("要点"), "图表", null);
    }

    @Test
    void appliesEditsAndRenumbersSlides() {
        PptOutline outline = new PptOutline(1, false, List.of(slide("一"), new SlideOutline(2, "二", "结论", List.of("要点"), "图表", null)));
        PptOutline next = outline.apply(List.of(
                new PptOutlineEdit(PptOutlineEditOperation.UPDATE, 1, slide("新版")),
                new PptOutlineEdit(PptOutlineEditOperation.DELETE, 2, null)));
        assertThat(next.version()).isEqualTo(2);
        assertThat(next.slides()).extracting(SlideOutline::slideNo).containsExactly(1);
        assertThat(next.slides().get(0).title()).isEqualTo("新版");
    }

    @Test
    void rejectsDeletingLastSlideAndEditingLockedOutline() {
        PptOutline outline = new PptOutline(1, false, List.of(slide("一")));
        assertThatThrownBy(() -> outline.apply(List.of(new PptOutlineEdit(PptOutlineEditOperation.DELETE, 1, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outline.lock().apply(List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void computesStructuredDiffForChangedFields() {
        PptOutline before = new PptOutline(1, false, List.of(slide("旧标题")));
        PptOutline after = new PptOutline(2, false, List.of(
                new SlideOutline(1, "新标题", "新结论", List.of("要点"), "新图表", null)));

        PptOutlineVersionDiff diff = PptOutlineVersionDiff.between(before, after);

        assertThat(diff.fromVersion()).isEqualTo(1);
        assertThat(diff.toVersion()).isEqualTo(2);
        assertThat(diff.changes()).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(PptOutlineDiffType.MODIFIED);
            assertThat(change.changedFields()).containsKeys("title", "keyMessage", "visualSuggestion");
        });
    }

    @Test
    void reportsMiddleInsertionWithoutTreatingRenumberedSlidesAsModified() {
        PptOutline before = new PptOutline(1, false, List.of(
                slide("一"), new SlideOutline(2, "二", "结论", List.of("要点"), "图表", null)));
        PptOutline after = new PptOutline(2, false, List.of(
                slide("一"), new SlideOutline(2, "新增", "新结论", List.of("新要点"), "新图表", null),
                new SlideOutline(3, "二", "结论", List.of("要点"), "图表", null)));

        assertThat(PptOutlineVersionDiff.between(before, after).changes())
                .extracting(PptOutlineSlideDiff::type)
                .containsExactly(PptOutlineDiffType.ADDED);
    }
}
