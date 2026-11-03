package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.engine.EngineResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Golden tests for the Java-authoritative {@code info} / {@code validate} port. */
class JavaProjectInfoValidateTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String SEPARATOR = "=".repeat(60);

    private final JavaProjectWorkspace workspace = new JavaProjectWorkspace();

    @TempDir
    Path tempDir;

    // ---------- info ----------

    @Test
    void infoFullProjectRendersAllFields() throws Exception {
        Path project = createProject("demo_ppt169_20261103");
        write(project, "README.md", "# demo");
        write(project, "design_spec.md", "spec");
        write(project, "sources/a.md", "a");
        write(project, "sources/b.md", "b");
        write(project, "svg_output/slide_1_main.svg", svg("0 0 1280 720"));
        write(project, "svg_output/slide_2_main.svg", svg("0 0 1280 720"));
        write(project, "svg_output/slide_3_main.svg", svg("0 0 1280 720"));

        EngineResult result = workspace.info(project, TIMEOUT);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .isEqualTo(
                        """

                        Project info: demo
                        %s
                        Path: %s
                        Exists: Yes
                        SVG files: 3
                        Design spec: Yes
                        Source materials: Yes
                        Source count: 2
                        Canvas format: PPT 16:9
                        Created: 2026-11-03
                        """
                                .formatted(SEPARATOR, project));
    }

    @Test
    void infoMissingProjectExitsZeroWithExistsNo() {
        Path project = tempDir.resolve("ghost_ppt169_20261103");

        EngineResult result = workspace.info(project, TIMEOUT);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("Project info: ghost")
                .contains("Path: " + project)
                .contains("Exists: No")
                .contains("SVG files: 0")
                .contains("Source count: 0");
    }

    @Test
    void infoBareDirectoryNameRendersUnknowns() throws Exception {
        Path project = createProject("demo");
        write(project, "README.md", "# demo");

        EngineResult result = workspace.info(project, TIMEOUT);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("Project info: demo")
                .contains("Canvas format: Unknown format")
                .contains("Created: Unknown date");
    }

    @Test
    void infoNormalizesFormatAliasInDirectoryName() throws Exception {
        Path project = createProject("demo_xhs_20261103");
        write(project, "README.md", "# demo");

        EngineResult result = workspace.info(project, TIMEOUT);

        assertThat(result.output())
                .contains("Project info: demo")
                .contains("Canvas format: 小红书");
    }

    @Test
    void infoLegacySpecFilenameCountsAsDesignSpec() throws Exception {
        Path project = createProject("demo_ppt169_20261103");
        write(project, "README.md", "# demo");
        write(project, "设计规范与内容大纲.md", "spec");

        EngineResult result = workspace.info(project, TIMEOUT);

        assertThat(result.output()).contains("Design spec: Yes");
    }

    @Test
    void infoLegacySourceFileMarksSourceMaterials() throws Exception {
        Path project = createProject("demo_ppt169_20261103");
        write(project, "README.md", "# demo");
        write(project, "来源文档.md", "source");

        EngineResult result = workspace.info(project, TIMEOUT);

        assertThat(result.output())
                .contains("Source materials: Yes")
                .contains("Source count: 0");
    }

    // ---------- validate ----------

    @Test
    void validateCompleteProjectPasses() throws Exception {
        Path project = createProject("demo_ppt169_20261103");
        write(project, "README.md", "# demo");
        write(project, "design_spec.md", "spec");
        write(project, "svg_output/slide_1_main.svg", svg("0 0 1280 720"));

        EngineResult result = workspace.validate(project, TIMEOUT);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .isEqualTo(
                        """

                        Project validation: %s
                        %s

                        [OK] Project structure is complete.
                        """
                                .formatted(project, SEPARATOR));
    }

    @Test
    void validateMissingReadmeIsInvalid() throws Exception {
        Path project = createProject("demo_ppt169_20261103");
        write(project, "design_spec.md", "spec");
        write(project, "svg_output/slide_1_main.svg", svg("0 0 1280 720"));

        EngineResult result = workspace.validate(project, TIMEOUT);

        assertThat(result.exitCode()).isOne();
        assertThat(result.output())
                .contains("[ERROR]\n  - Missing required file: README.md")
                .contains("[ERROR] Project structure is invalid.");
    }

    @Test
    void validateMissingSvgOutputIsInvalid() throws Exception {
        Path project = createProject("demo_ppt169_20261103");
        write(project, "README.md", "# demo");
        write(project, "design_spec.md", "spec");

        EngineResult result = workspace.validate(project, TIMEOUT);

        assertThat(result.exitCode()).isOne();
        assertThat(result.output()).contains("  - Missing svg_output directory");
    }

    @Test
    void validateEmptySvgOutputWarns() throws Exception {
        Path project = createProject("demo_ppt169_20261103");
        write(project, "README.md", "# demo");
        write(project, "design_spec.md", "spec");
        Files.createDirectories(project.resolve("svg_output"));

        EngineResult result = workspace.validate(project, TIMEOUT);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("  - svg_output directory is empty, no SVG files found");
    }

    @Test
    void validateNonStandardSvgNamingWarns() throws Exception {
        Path project = createProject("demo_ppt169_20261103");
        write(project, "README.md", "# demo");
        write(project, "design_spec.md", "spec");
        write(project, "svg_output/weird.svg", svg("0 0 1280 720"));

        EngineResult result = workspace.validate(project, TIMEOUT);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("  - Non-standard SVG file naming: weird.svg");
    }

    @Test
    void validateMissingDateSuffixWarns() throws Exception {
        Path project = createProject("demo_ppt169");
        write(project, "README.md", "# demo");
        write(project, "design_spec.md", "spec");
        write(project, "svg_output/slide_1_main.svg", svg("0 0 1280 720"));

        EngineResult result = workspace.validate(project, TIMEOUT);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("  - Directory name missing date suffix (_YYYYMMDD): demo_ppt169");
    }

    @Test
    void validateWarningsOnlyIsValidWithWarnings() throws Exception {
        Path project = createProject("demo_ppt169_20261103");
        write(project, "README.md", "# demo");
        write(project, "svg_output/slide_1_main.svg", svg("0 0 1280 720"));

        EngineResult result = workspace.validate(project, TIMEOUT);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("  - Missing design specification file (suggested filename: design_spec.md)")
                .endsWith("[OK] Project structure is valid, with warnings.\n");
    }

    @Test
    void validateMissingProjectIsInvalid() {
        Path project = tempDir.resolve("ghost_ppt169_20261103");

        EngineResult result = workspace.validate(project, TIMEOUT);

        assertThat(result.exitCode()).isOne();
        assertThat(result.output())
                .contains("  - Project directory does not exist: " + project)
                .endsWith("[ERROR] Project structure is invalid.\n");
    }

    // ---------- viewBox ----------

    @Test
    void validateMatchingViewBoxProducesNoWarning() throws Exception {
        Path project = createProject("demo_ppt169_20261103");
        write(project, "README.md", "# demo");
        write(project, "design_spec.md", "spec");
        write(project, "svg_output/slide_1_main.svg", svg("0 0 1280 720"));

        EngineResult result = workspace.validate(project, TIMEOUT);

        assertThat(result.output()).doesNotContain("viewBox");
    }

    @Test
    void validateMismatchedViewBoxWarnsWithExpectedValue() throws Exception {
        Path project = createProject("demo_ppt169_20261103");
        write(project, "README.md", "# demo");
        write(project, "design_spec.md", "spec");
        write(project, "svg_output/slide_1_main.svg", svg("0 0 1024 768"));

        EngineResult result = workspace.validate(project, TIMEOUT);

        assertThat(result.output())
                .contains(
                        "  - slide_1_main.svg: viewBox '0 0 1024 768' does not match expected format "
                                + "'ppt169' (expected: '0 0 1280 720')");
    }

    @Test
    void validateMissingViewBoxWarns() throws Exception {
        Path project = createProject("demo_ppt169_20261103");
        write(project, "README.md", "# demo");
        write(project, "design_spec.md", "spec");
        write(project, "svg_output/slide_1_main.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>");

        EngineResult result = workspace.validate(project, TIMEOUT);

        assertThat(result.output()).contains("  - slide_1_main.svg: viewBox attribute not found");
    }

    @Test
    void validateMultipleViewBoxesWarns() throws Exception {
        Path project = createProject("demo");
        write(project, "README.md", "# demo");
        write(project, "design_spec.md", "spec");
        write(project, "svg_output/slide_1_main.svg", svg("0 0 100 100"));
        write(project, "svg_output/slide_2_main.svg", svg("0 0 200 200"));

        EngineResult result = workspace.validate(project, TIMEOUT);

        assertThat(result.output())
                .contains("Multiple different viewBox settings detected: {'0 0 100 100', '0 0 200 200'}");
    }

    @Test
    void validateUnknownFormatSkipsExpectedViewBoxComparison() throws Exception {
        Path project = createProject("demo");
        write(project, "README.md", "# demo");
        write(project, "design_spec.md", "spec");
        // Consistent but arbitrary viewBox: no expected comparison, no warnings.
        write(project, "svg_output/slide_1_main.svg", svg("0 0 999 999"));

        EngineResult result = workspace.validate(project, TIMEOUT);

        assertThat(result.output()).doesNotContain("viewBox");
    }

    @Test
    void validateUnknownFormatStillWarnsOnMissingViewBox() throws Exception {
        Path project = createProject("demo");
        write(project, "README.md", "# demo");
        write(project, "design_spec.md", "spec");
        write(project, "svg_output/slide_1_main.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>");

        EngineResult result = workspace.validate(project, TIMEOUT);

        assertThat(result.output()).contains("  - slide_1_main.svg: viewBox attribute not found");
    }

    // ---------- fixtures ----------

    private Path createProject(String dirName) throws Exception {
        Path project = tempDir.resolve(dirName);
        Files.createDirectories(project);
        return project;
    }

    private static void write(Path project, String relative, String content) throws Exception {
        Path target = project.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static String svg(String viewBox) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"" + viewBox + "\"></svg>";
    }
}
