package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.exception.PptJobStateException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateFillPlanValidatorTests {

    @TempDir
    Path tempDir;

    private final TemplateFillPlanValidator validator = new TemplateFillPlanValidator();

    @Test
    void acceptsValidDraftAgainstSlideLibrary() throws Exception {
        Path slideLibrary = copyResource("template.slide_library.json");
        String plan = Files.readString(Path.of(getClass().getResource("/template-fill/fill-plan-draft-valid.json").toURI()));

        var parsed = validator.parseAndValidateDraft(plan, slideLibrary);

        assertThat(parsed.get("status").asText()).isEqualTo("draft");
        assertThat(parsed.get("slides")).hasSize(2);
    }

    @Test
    void rejectsInvalidTemplateReferences() throws Exception {
        Path slideLibrary = copyResource("template.slide_library.json");
        String plan = Files.readString(Path.of(getClass().getResource("/template-fill/fill-plan-draft-invalid-refs.json").toURI()));

        assertThatThrownBy(() -> validator.parseAndValidateDraft(plan, slideLibrary))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("unknown template slide");
    }

    @Test
    void rejectsConfirmedStatusFromAgent() throws Exception {
        Path slideLibrary = copyResource("template.slide_library.json");

        assertThatThrownBy(() -> validator.parseAndValidateDraft(
                "{\"status\":\"confirmed\",\"slides\":[{\"outputOrder\":1,\"templateSlideIndex\":1}]}", slideLibrary))
                .isInstanceOf(PptJobStateException.class)
                .hasMessageContaining("draft");
    }

    private Path copyResource(String name) throws IOException {
        Path target = tempDir.resolve(name);
        Files.copy(getClass().getResourceAsStream("/template-fill/" + name), target);
        return target;
    }
}
