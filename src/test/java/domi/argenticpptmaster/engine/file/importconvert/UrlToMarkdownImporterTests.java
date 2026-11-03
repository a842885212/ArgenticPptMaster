package domi.argenticpptmaster.engine.file.importconvert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UrlToMarkdownImporterTests {

    @TempDir
    Path tempDir;

    @Test
    void importsStaticHtmlWithRelativeImagesAndManifest() throws Exception {
        String html =
                """
                <html><head><title>Demo Page</title>
                <meta name="description" content="A demo"/>
                </head><body>
                <article>
                  <p>Hello world content that is long enough for extraction scoring to pick article.</p>
                  <p>Second paragraph with more text for the content length threshold.</p>
                  <img data-src="/img/a.png" alt="A">
                  <img src="https://cdn.example.com/missing.png" alt="skip">
                </article>
                </body></html>
                """;
        AtomicInteger imageCalls = new AtomicInteger();
        UrlToMarkdownImporter importer = new UrlToMarkdownImporter(
                uri -> new UrlToMarkdownImporter.FetchedPage(html),
                uri -> {
                    imageCalls.incrementAndGet();
                    if (uri.toString().contains("missing")) {
                        throw new ImportConversionException("boom");
                    }
                    return new UrlToMarkdownImporter.FetchedBytes(
                            new byte[] {1, 2, 3, 4}, "image/png");
                });

        Path markdown = tempDir.resolve("demo.md");
        importer.importUrl("https://example.com/posts/demo", markdown);

        String md = Files.readString(markdown, StandardCharsets.UTF_8);
        assertThat(md).contains("# Demo Page");
        assertThat(md).contains("Hello world content");
        assertThat(md).contains("![A](demo_files/");
        assertThat(md).contains("Source: https://example.com/posts/demo");
        Path assetDir = tempDir.resolve("demo_files");
        assertThat(assetDir).isDirectory();
        assertThat(assetDir.resolve("image_manifest.json")).exists();
        String manifest = Files.readString(assetDir.resolve("image_manifest.json"));
        assertThat(manifest).contains("\"source_kind\" : \"web_image\"");
        assertThat(imageCalls.get()).isEqualTo(2);
    }

    @Test
    void pageFailureLeavesNoSuccessMarkdown() {
        UrlToMarkdownImporter importer = new UrlToMarkdownImporter(
                uri -> {
                    throw new ImportConversionException("fetch failed");
                },
                uri -> new UrlToMarkdownImporter.FetchedBytes(new byte[0], "image/png"));

        assertThatThrownBy(() -> importer.importUrl("https://example.com/x", tempDir.resolve("x.md")))
                .isInstanceOf(ImportConversionException.class)
                .hasMessageContaining("fetch failed");
    }

    @Test
    void writeUrlFailureRecordCreatesUrlTxt() throws Exception {
        Path record = UrlToMarkdownImporter.writeUrlFailureRecord(tempDir, "https://example.com/a/b");
        assertThat(record.getFileName().toString()).endsWith(".url.txt");
        assertThat(Files.readString(record)).contains("URL: https://example.com/a/b");
    }

    @Test
    void ssrfRejectsLoopback() {
        assertThatThrownBy(() -> UrlSsrfGuard.validateResolvedTarget(
                        java.net.URI.create("http://127.0.0.1/secret")))
                .isInstanceOf(ImportConversionException.class)
                .hasMessageContaining("private/loopback");
    }

    @Test
    void ssrfRejectsNonHttp() {
        assertThatThrownBy(() -> UrlSsrfGuard.requireHttpUrl("file:///etc/passwd"))
                .isInstanceOf(ImportConversionException.class)
                .hasMessageContaining("http/https");
    }
}
