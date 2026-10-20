package domi.argenticpptmaster.engine.ooxml;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OoxmlPackageHelpersTests {

    @TempDir
    Path tempDir;

    @Test
    void extractAndZipRoundTripPreservesParts() throws Exception {
        Path pptx = PptxFixtureSupport.writeOneSlidePptx(tempDir.resolve("source.pptx"));
        Path extract = tempDir.resolve("extract");
        PptxPackage.extract(pptx, extract);

        assertThat(extract.resolve("ppt/slides/slide1.xml")).exists();
        List<SlidePartRef> slides = SlideParts.read(extract);
        assertThat(slides).hasSize(1);
        assertThat(slides.get(0).partName()).isEqualTo("ppt/slides/slide1.xml");

        Path repacked = tempDir.resolve("repacked.pptx");
        PptxPackage.zipDirectory(extract, repacked);
        try (ZipFile zip = new ZipFile(repacked.toFile())) {
            assertThat(zip.getEntry("ppt/slides/slide1.xml")).isNotNull();
            assertThat(zip.getEntry("ppt/theme/theme1.xml")).isNotNull();
        }
    }

    @Test
    void contentTypesOverrideIsIdempotent() {
        String base =
                """
                <?xml version="1.0"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                </Types>
                """;
        String once = ContentTypesSupport.addOverride(
                base, "ppt/notesSlides/notesSlide1.xml", OoxmlConstants.CONTENT_TYPE_NOTES_SLIDE);
        String twice = ContentTypesSupport.addOverride(
                once, "ppt/notesSlides/notesSlide1.xml", OoxmlConstants.CONTENT_TYPE_NOTES_SLIDE);
        assertThat(twice).isEqualTo(once);
        assertThat(once).contains("PartName=\"/ppt/notesSlides/notesSlide1.xml\"");
    }

    @Test
    void markdownNotesStripsHeadingsAndBold() {
        String plain = MarkdownNotes.toPlainText("# Title\n\n- **item**\n\nplain");
        assertThat(plain).isEqualTo("Title\n\n• item\n\nplain");
    }

    @Test
    void notesApplierWritesNotesSlideAndContentTypes() throws Exception {
        Path pptx = PptxFixtureSupport.writeOneSlidePptx(tempDir.resolve("deck.pptx"));
        Path extract = tempDir.resolve("notes-extract");
        PptxPackage.extract(pptx, extract);
        Path notesDir = tempDir.resolve("notes");
        Files.createDirectories(notesDir);
        Files.writeString(notesDir.resolve("001.md"), "Hello **world**\n", StandardCharsets.UTF_8);

        List<SlidePartRef> slides = SlideParts.read(extract);
        Set<String> parts = NotesApplier.applyNotesFromDirectory(extract, notesDir, slides);

        assertThat(parts).contains("ppt/notesSlides/notesSlide1.xml");
        assertThat(extract.resolve("ppt/notesSlides/notesSlide1.xml")).exists();
        assertThat(Files.readString(extract.resolve("ppt/notesSlides/notesSlide1.xml")))
                .contains("Hello world");
        assertThat(Files.readString(extract.resolve("[Content_Types].xml")))
                .contains("notesSlide1.xml")
                .contains("notesMaster1.xml");
        assertThat(extract.resolve("ppt/notesMasters/notesMaster1.xml")).exists();
        assertThat(extract.resolve("ppt/theme/theme2.xml")).exists();
    }
}
