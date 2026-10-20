package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.engine.EngineResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaProjectWorkspaceTests {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-03-15T08:00:00+08:00"), ZONE);

    @TempDir
    Path tempDir;

    private JavaProjectWorkspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new JavaProjectWorkspace(FIXED_CLOCK);
    }

    @Test
    void initCreatesPpt169ProjectWithOkLineAndDirectories() throws Exception {
        Path projectsDir = tempDir.resolve("projects");

        EngineResult result = workspace.init("demo", "ppt169", projectsDir, Duration.ofSeconds(30));

        assertThat(result.successful()).isTrue();
        Path project = projectsDir.resolve("demo_ppt169_20260315");
        assertThat(project).isDirectory();
        assertThat(result.output()).contains("Project created: " + project);
        assertThat(result.output()).contains("Canvas: PPT 16:9 (1280×720)");
        assertThat(result.output()).contains("[OK] Project initialized: " + project);
        assertThat(result.output()).contains("Next:");
        assertThat(Files.readString(project.resolve("README.md"), StandardCharsets.UTF_8))
                .contains("- Canvas format: ppt169")
                .contains("- Created: 20260315");
        for (String dir :
                List.of("svg_output", "sources", "validation", "images", "exports")) {
            assertThat(project.resolve(dir)).isDirectory();
        }
        String log = Files.readString(project.resolve("validation/workflow.log"), StandardCharsets.UTF_8);
        assertThat(log).contains("Project initialized: profile=default; canvas=ppt169; path=" + project);
    }

    @Test
    void initAcceptsXhsAlias() {
        Path projectsDir = tempDir.resolve("projects");

        EngineResult result = workspace.init("deck", "xhs", projectsDir, Duration.ofSeconds(5));

        assertThat(result.successful()).isTrue();
        assertThat(projectsDir.resolve("deck_xiaohongshu_20260315")).isDirectory();
        assertThat(result.output()).contains("Canvas: 小红书 (1242×1660)");
    }

    @Test
    void initRejectsIllegalProjectName() {
        EngineResult result = workspace.init("../evil", "ppt169", tempDir, Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).isEqualTo("[ERROR] Project name must be a single, non-absolute path component");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void initRejectsExistingDirectory() throws Exception {
        Path projectsDir = tempDir.resolve("projects");
        Files.createDirectories(projectsDir.resolve("demo_ppt169_20260315"));

        EngineResult result = workspace.init("demo", "ppt169", projectsDir, Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("Project directory already exists:");
    }

    @Test
    void initRejectsUnknownFormat() {
        EngineResult result = workspace.init("demo", "not-a-format", tempDir, Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("Unsupported canvas format: not-a-format");
        assertThat(result.output()).contains("common alias: xhs -> xiaohongshu");
        assertThat(result.output()).contains("a4, banner, moments, ppt169, ppt43, story, wechat, xiaohongshu");
    }

    @Test
    void importSourcesCopiesMarkdownIntoSources() throws Exception {
        Path project = initProject("mdproj", "ppt169");
        Path source = tempDir.resolve("article.md");
        Files.writeString(source, "# Title\n\nBody\n", StandardCharsets.UTF_8);

        EngineResult result = workspace.importSources(project, List.of(source), true, Duration.ofSeconds(10));

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).contains("[OK] Imported sources into: " + project);
        assertThat(result.output()).contains("Normalized markdown:");
        Path imported = project.resolve("sources/article.md");
        assertThat(imported).isRegularFile();
        assertThat(Files.readString(imported, StandardCharsets.UTF_8)).contains("# Title");
        assertThat(source).isRegularFile();
    }

    @Test
    void importSourcesCopiesPngToImagesAndSources() throws Exception {
        Path project = initProject("imgproj", "ppt169");
        Path source = tempDir.resolve("photo.png");
        Files.write(source, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});

        EngineResult result = workspace.importSources(project, List.of(source), true, Duration.ofSeconds(10));

        assertThat(result.successful()).isTrue();
        assertThat(project.resolve("sources/photo.png")).isRegularFile();
        assertThat(project.resolve("images/photo.png")).isRegularFile();
        assertThat(result.output()).contains("Runtime image copies:");
    }

    @Test
    void importSourcesRecordsMissingPathInSkipped() throws Exception {
        Path project = initProject("skipproj", "ppt169");
        Path missing = tempDir.resolve("missing.md");

        EngineResult result = workspace.importSources(project, List.of(missing), true, Duration.ofSeconds(10));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("Skipped:");
        assertThat(result.output()).contains(missing + ": path not found");
    }

    @Test
    void importSourcesArchivesPptxWithoutConversion() throws Exception {
        Path project = initProject("pptxproj", "ppt169");
        Path source = tempDir.resolve("deck.pptx");
        Files.write(source, new byte[] {0x50, 0x4B, 0x03, 0x04});

        EngineResult result = workspace.importSources(project, List.of(source), true, Duration.ofSeconds(10));

        assertThat(result.successful()).isTrue();
        assertThat(project.resolve("sources/deck.pptx")).isRegularFile();
        assertThat(result.output()).contains("Archived originals:");
        assertThat(result.output()).contains("automatic conversion is not provided by this Java engine");
    }

    @Test
    void infoIsNotImplemented() throws Exception {
        Path project = initProject("infoproj", "ppt169");

        EngineResult result = workspace.info(project, Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).isEqualTo("[ERROR] info is still provided by the Python project workspace");
    }

    private Path initProject(String name, String format) {
        Path projectsDir = tempDir.resolve("projects-" + name);
        EngineResult init = workspace.init(name, format, projectsDir, Duration.ofSeconds(30));
        assertThat(init.successful()).isTrue();
        return projectsDir.resolve(name + "_" + format + "_20260315");
    }
}
