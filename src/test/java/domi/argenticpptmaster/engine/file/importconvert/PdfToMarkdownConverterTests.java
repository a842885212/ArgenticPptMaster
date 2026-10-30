package domi.argenticpptmaster.engine.file.importconvert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfToMarkdownConverterTests {

    @TempDir
    Path tempDir;

    @Test
    void convertsTextPdfWithPageMarkersAndTitle() throws Exception {
        Path pdf = tempDir.resolve("01-Quarterly Report.pdf");
        writeTwoPageTextPdf(pdf);
        Path md = tempDir.resolve("out.md");

        PdfToMarkdownConverter.convert(pdf, md);

        String content = Files.readString(md);
        assertThat(content).startsWith("# Quarterly Report");
        assertThat(content).contains("<!-- Page 2 -->");
        assertThat(content).contains("Introduction body text for page one.");
        assertThat(content).contains("Second page continues the narrative.");
    }

    @Test
    void failsClosedOnNearEmptyPdf() throws Exception {
        Path pdf = tempDir.resolve("blankish.pdf");
        writeNearEmptyPdf(pdf);
        Path md = tempDir.resolve("blank.md");

        assertThatThrownBy(() -> PdfToMarkdownConverter.convert(pdf, md))
                .isInstanceOf(ImportConversionException.class)
                .hasMessageContaining("insufficient extractable text");
    }

    @Test
    void failsClosedOnEncryptedPdf() throws Exception {
        Path pdf = tempDir.resolve("secret.pdf");
        writeEncryptedPdf(pdf);
        Path md = tempDir.resolve("secret.md");

        assertThatThrownBy(() -> PdfToMarkdownConverter.convert(pdf, md))
                .isInstanceOf(ImportConversionException.class)
                .hasMessageContaining("encrypted");
    }

    @Test
    void extractsFilteredImageAndManifest() throws Exception {
        Path pdf = tempDir.resolve("with-image.pdf");
        writePdfWithLargeImage(pdf);
        Path md = tempDir.resolve("with-image.md");

        PdfToMarkdownConverter.convert(pdf, md);

        Path assetDir = tempDir.resolve("with-image_files");
        assertThat(assetDir).isDirectory();
        Path manifest = assetDir.resolve("image_manifest.json");
        assertThat(manifest).isRegularFile();
        String manifestJson = Files.readString(manifest);
        assertThat(manifestJson).contains("\"source_kind\" : \"pdf_image\"");
        assertThat(manifestJson).contains("\"asset_kind\" : \"bitmap\"");
        assertThat(Files.readString(md)).contains("_files/");
        assertThat(Files.list(assetDir).filter(p -> p.getFileName().toString().endsWith(".png")).count())
                .isGreaterThanOrEqualTo(1);
    }

    private static void writeTwoPageTextPdf(Path pdf) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            PDPage page1 = new PDPage(PDRectangle.LETTER);
            doc.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(bold, 20);
                cs.newLineAtOffset(72, 720);
                cs.showText("Overview Heading");
                cs.endText();
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 690);
                cs.showText("Introduction body text for page one.");
                cs.endText();
            }

            PDPage page2 = new PDPage(PDRectangle.LETTER);
            doc.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Second page continues the narrative.");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
    }

    private static void writeNearEmptyPdf(Path pdf) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("x");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
    }

    private static void writeEncryptedPdf(Path pdf) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("This is enough visible characters to pass empty checks if unlocked.");
                cs.endText();
            }
            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy spp = new StandardProtectionPolicy("owner", "user", ap);
            spp.setEncryptionKeyLength(128);
            doc.protect(spp);
            doc.save(pdf.toFile());
        }
    }

    private static void writePdfWithLargeImage(Path pdf) throws Exception {
        // Noisy pixels keep bytes-per-pixel above the low-info filter used by pdf_to_md.
        BufferedImage image = new BufferedImage(240, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        for (int y = 0; y < 180; y++) {
            for (int x = 0; x < 240; x++) {
                g.setColor(new Color((x * 37 + y * 17) & 0xFF, (x * 11) & 0xFF, (y * 29) & 0xFF));
                g.fillRect(x, y, 1, 1);
            }
        }
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bos);
        byte[] png = bos.toByteArray();

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDImageXObject pdImage = PDImageXObject.createFromByteArray(doc, png, "fig");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Document with an embedded figure for conversion tests.");
                cs.endText();
                cs.drawImage(pdImage, 72, 400, 240, 180);
            }
            doc.save(pdf.toFile());
        }
    }
}
