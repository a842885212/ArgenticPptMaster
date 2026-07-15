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
}
