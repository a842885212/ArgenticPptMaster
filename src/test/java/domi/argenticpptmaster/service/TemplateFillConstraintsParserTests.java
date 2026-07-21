package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.domain.TemplateFillConstraints;
import domi.argenticpptmaster.exception.PptJobStateException;
import org.junit.jupiter.api.Test;

class TemplateFillConstraintsParserTests {

    private final TemplateFillConstraintsParser parser = new TemplateFillConstraintsParser();

    @Test
    void parsesValidConstraints() {
        TemplateFillConstraints constraints = parser.parse("""
                {"allowedTemplateSlides":[1,2],"excludedTemplateSlides":[3],
                "preserveCover":true,"preserveEnding":false,"maxSlides":5}
                """);
        assertThat(constraints.allowedTemplateSlides()).containsExactly(1, 2);
        assertThat(constraints.excludedTemplateSlides()).containsExactly(3);
        assertThat(constraints.preserveCover()).isTrue();
        assertThat(constraints.maxSlides()).isEqualTo(5);
    }

    @Test
    void rejectsUnknownFieldsAndIntersections() {
        assertThatThrownBy(() -> parser.parse("{\"foo\":1}"))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("unknown field");
        assertThatThrownBy(() -> parser.parse(
                "{\"allowedTemplateSlides\":[1],\"excludedTemplateSlides\":[1]}"))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("intersect");
    }
}
