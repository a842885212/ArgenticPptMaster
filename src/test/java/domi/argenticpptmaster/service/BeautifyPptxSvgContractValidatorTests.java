package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BeautifyPptxSvgContractValidatorTests {

    @TempDir
    Path tempDir;

    @Test
    void acceptsOneToOneSvgPagesWithVerbatimText() throws Exception {
        writeInventory("[{\"slide_index\":1,\"text_blocks\":[{\"text\":\"Repeated\"},{\"text\":\"Repeated\"}]}]");
        writeSvg("01_baseline.svg", "<svg><text>Repeated</text><text>Repeated</text></svg>");

        assertThatCode(() -> BeautifyPptxSvgContractValidator.validate(tempDir)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrGeneratedImageBackedSvgContent() throws Exception {
        writeInventory("[{\"slide_index\":1,\"text_blocks\":[{\"text\":\"Required\"}],\"images\":[]}]");
        writeSvg("slide-1.svg", "<svg><image href=\"https://example.test/image.png\"/><text>Required</text></svg>");

        assertThatThrownBy(() -> BeautifyPptxSvgContractValidator.validate(tempDir))
                .hasMessageContaining("BEAUTIFY_SVG_CONTRACT_FAILED");
    }

    @Test
    void acceptsBoundProjectImageAndFrozenTableData() throws Exception {
        Files.createDirectories(tempDir.resolve("images"));
        Files.writeString(tempDir.resolve("images/a.png"), "png");
        writeInventory("[{\"slide_index\":1,\"text_blocks\":[{\"text\":\"Title\"}],"
                + "\"images\":[{\"id\":\"img-1\",\"filename\":\"a.png\"}],"
                + "\"tables\":[{\"id\":\"t1\",\"rows\":[[\"A\",\"B\"]]}],"
                + "\"charts\":[]}]");
        writeSvg("slide-1.svg", "<svg><image href=\"images/a.png\"/><text>Title</text><text>A</text><text>B</text></svg>");

        assertThatCode(() -> BeautifyPptxSvgContractValidator.validate(tempDir)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNamespacedNonTextSvgElements() throws Exception {
        writeInventory("[{\"slide_index\":1,\"text_blocks\":[{\"text\":\"Required\"}],\"images\":[]}]");
        writeSvg("slide-1.svg", "<svg:svg xmlns:svg=\"http://www.w3.org/2000/svg\"><svg:text>Required</svg:text>"
                + "<svg:image href=\"data:image/png;base64,AA==\"/></svg:svg>");

        assertThatThrownBy(() -> BeautifyPptxSvgContractValidator.validate(tempDir))
                .hasMessageContaining("BEAUTIFY_SVG_CONTRACT_FAILED");
    }

    @Test
    void rejectsLegacyTextLedgerSchema() throws Exception {
        writeInventory("[{\"slide_index\":1,\"text\":[\"Required\"]}]");
        writeSvg("slide-1.svg", "<svg><text>Required</text></svg>");

        assertThatThrownBy(() -> BeautifyPptxSvgContractValidator.validate(tempDir))
                .hasMessageContaining("inventory text ledger is missing");
    }

    private void writeInventory(String slides) throws Exception {
        Path analysis = Files.createDirectories(tempDir.resolve("analysis"));
        Files.writeString(analysis.resolve("beautify_inventory.json"),
                "{\"slide_count\":1,\"slides\":" + slides + "}");
    }

    private void writeSvg(String filename, String content) throws Exception {
        Path output = Files.createDirectories(tempDir.resolve("svg_output"));
        Files.writeString(output.resolve(filename), content);
    }
}
