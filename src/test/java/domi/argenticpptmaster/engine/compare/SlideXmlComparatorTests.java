package domi.argenticpptmaster.engine.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlideXmlComparatorTests {

    @TempDir
    Path tempDir;

    @Test
    void treatsDifferingRelationshipIdsAsEqualAfterNormalize() throws IOException {
        Path left = writePptx(tempDir.resolve("left.pptx"),
                """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <p:cSld><p:spTree>
                    <p:pic><p:blipFill><a:blip r:embed="rId2"/></p:blipFill></p:pic>
                  </p:spTree></p:cSld>
                </p:sld>
                """);
        Path right = writePptx(tempDir.resolve("right.pptx"),
                """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <p:cSld><p:spTree>
                    <p:pic><p:blipFill><a:blip r:embed="rId9"/></p:blipFill></p:pic>
                  </p:spTree></p:cSld>
                </p:sld>
                """);

        SlideXmlComparator.Result result = new SlideXmlComparator().compare(left, right);

        assertThat(result.equal()).isTrue();
        assertThat(result.diffs()).isEmpty();
    }

    @Test
    void reportsMismatchWhenSlideContentChanges() throws IOException {
        Path left = writePptx(tempDir.resolve("left.pptx"),
                """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld><p:spTree><p:sp><p:txBody><a:t>Hello</a:t></p:txBody></p:sp></p:spTree></p:cSld>
                </p:sld>
                """);
        Path right = writePptx(tempDir.resolve("right.pptx"),
                """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld><p:spTree><p:sp><p:txBody><a:t>World</a:t></p:txBody></p:sp></p:spTree></p:cSld>
                </p:sld>
                """);

        SlideXmlComparator.Result result = new SlideXmlComparator().compare(left, right);

        assertThat(result.equal()).isFalse();
        assertThat(result.diffs()).singleElement().satisfies(diff -> {
            assertThat(diff.partName()).isEqualTo("ppt/slides/slide1.xml");
            assertThat(diff.leftNormalized()).contains("Hello");
            assertThat(diff.rightNormalized()).contains("World");
        });
    }

    private static Path writePptx(Path path, String slideXml) throws IOException {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<?xml version=\"1.0\"?><Types></Types>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ppt/slides/slide1.xml"));
            zip.write(slideXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }
}
