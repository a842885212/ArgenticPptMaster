package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.file.svgquality.JavaSvgQualityChecker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PinnedPythonGoldenComparisonTests {

    private Path pinRoot;
    private Path python;
    private Path projectManagerScript;
    private Path totalMdSplitScript;
    private Path svgQualityCheckerScript;

    @BeforeEach
    void assumePinnedEnvironment() {
        pinRoot = GoldenComparisonSupport.pinRoot();
        python = GoldenComparisonSupport.pythonExecutable();
        projectManagerScript = GoldenComparisonSupport.projectManagerScript(pinRoot);
        totalMdSplitScript = GoldenComparisonSupport.totalMdSplitScript(pinRoot);
        svgQualityCheckerScript = GoldenComparisonSupport.svgQualityCheckerScript(pinRoot);
        assumeTrue(GoldenComparisonSupport.goldenEnvironmentReady(
                pinRoot, python, projectManagerScript, totalMdSplitScript, svgQualityCheckerScript));
    }

    @Test
    void initGoldenPpt169MatchesPinnedPython(@TempDir Path tempDir) throws Exception {
        Path pythonProjects = tempDir.resolve("py-projects");
        Files.createDirectories(pythonProjects);

        GoldenComparisonSupport.ProcessResult pythonRun = GoldenComparisonSupport.runPython(
                python,
                projectManagerScript,
                List.of("init", "golden", "--format", "ppt169", "--dir", pythonProjects.toString()));

        assertThat(pythonRun.timedOut()).isFalse();
        Path pythonProject = GoldenComparisonSupport.parseProjectCreatedPath(pythonRun.output());
        String date = GoldenComparisonSupport.extractDateFromProjectDir(pythonProject);

        Path javaProjects = tempDir.resolve("java-projects");
        Files.createDirectories(javaProjects);
        Clock clock = GoldenComparisonSupport.clockAtLocalNoon(date);
        JavaProjectWorkspace javaWorkspace = new JavaProjectWorkspace(clock);

        EngineResult javaResult =
                javaWorkspace.init("golden", "ppt169", javaProjects, Duration.ofSeconds(60));
        Path javaProject = javaProjects.resolve("golden_ppt169_" + date);

        assertThat(pythonRun.exitCode()).isEqualTo(javaResult.exitCode());
        assertThat(javaResult.timedOut()).isFalse();

        assertThat(GoldenComparisonSupport.listRelativePaths(javaProject))
                .isEqualTo(GoldenComparisonSupport.listRelativePaths(pythonProject));

        assertThat(Files.readString(javaProject.resolve("README.md"), StandardCharsets.UTF_8))
                .isEqualTo(Files.readString(pythonProject.resolve("README.md"), StandardCharsets.UTF_8));

        String pythonLog =
                Files.readString(pythonProject.resolve("validation/workflow.log"), StandardCharsets.UTF_8);
        String javaLog =
                Files.readString(javaProject.resolve("validation/workflow.log"), StandardCharsets.UTF_8);
        assertThat(GoldenComparisonSupport.normalizeWorkflowLog(javaLog, javaProject))
                .isEqualTo(GoldenComparisonSupport.normalizeWorkflowLog(pythonLog, pythonProject));

        assertThat(GoldenComparisonSupport.normalizeCommandOutput(javaResult.output(), javaProject))
                .isEqualTo(GoldenComparisonSupport.normalizeCommandOutput(pythonRun.output(), pythonProject));
    }

    @Test
    void initXhsUsesXiaohongshuDirectorySuffix(@TempDir Path tempDir) throws Exception {
        Path pythonProjects = tempDir.resolve("py-xhs");
        Files.createDirectories(pythonProjects);

        GoldenComparisonSupport.ProcessResult pythonRun = GoldenComparisonSupport.runPython(
                python,
                projectManagerScript,
                List.of("init", "deck", "--format", "xhs", "--dir", pythonProjects.toString()));

        Path pythonProject = GoldenComparisonSupport.parseProjectCreatedPath(pythonRun.output());
        String date = GoldenComparisonSupport.extractDateFromProjectDir(pythonProject);
        assertThat(pythonProject.getFileName().toString()).isEqualTo("deck_xiaohongshu_" + date);

        Path javaProjects = tempDir.resolve("java-xhs");
        JavaProjectWorkspace javaWorkspace =
                new JavaProjectWorkspace(GoldenComparisonSupport.clockAtLocalNoon(date));
        EngineResult javaResult = javaWorkspace.init("deck", "xhs", javaProjects, Duration.ofSeconds(60));

        Path javaProject = javaProjects.resolve("deck_xiaohongshu_" + date);
        assertThat(javaProject).isDirectory();
        assertThat(pythonRun.exitCode()).isEqualTo(javaResult.exitCode());
        assertThat(GoldenComparisonSupport.normalizeCommandOutput(javaResult.output(), javaProject))
                .isEqualTo(GoldenComparisonSupport.normalizeCommandOutput(pythonRun.output(), pythonProject));
    }

    @Test
    void importSourcesMatchesPinnedPython(@TempDir Path tempDir) throws Exception {
        Path article = tempDir.resolve("golden_article.md");
        Path photo = tempDir.resolve("golden_photo.png");
        Path note = tempDir.resolve("golden_note.txt");
        Files.writeString(article, "# Title\n\nBody\n", StandardCharsets.UTF_8);
        Files.write(photo, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        Files.writeString(note, "plain text\n", StandardCharsets.UTF_8);

        Path pythonProjects = tempDir.resolve("py-import");
        Files.createDirectories(pythonProjects);
        GoldenComparisonSupport.ProcessResult pythonInit = GoldenComparisonSupport.runPython(
                python,
                projectManagerScript,
                List.of("init", "imp", "--format", "ppt169", "--dir", pythonProjects.toString()));
        Path pythonProject = GoldenComparisonSupport.parseProjectCreatedPath(pythonInit.output());
        String date = GoldenComparisonSupport.extractDateFromProjectDir(pythonProject);

        GoldenComparisonSupport.ProcessResult pythonImport = GoldenComparisonSupport.runPython(
                python,
                projectManagerScript,
                List.of(
                        "import-sources",
                        pythonProject.toString(),
                        article.toString(),
                        photo.toString(),
                        note.toString(),
                        "--copy"));

        Path javaProjects = tempDir.resolve("java-import");
        JavaProjectWorkspace javaWorkspace =
                new JavaProjectWorkspace(GoldenComparisonSupport.clockAtLocalNoon(date));
        EngineResult javaInit = javaWorkspace.init("imp", "ppt169", javaProjects, Duration.ofSeconds(60));
        assertThat(javaInit.successful()).isTrue();
        Path javaProject = javaProjects.resolve("imp_ppt169_" + date);

        EngineResult javaImport = javaWorkspace.importSources(
                javaProject, List.of(article, photo, note), true, Duration.ofSeconds(60));

        assertThat(pythonImport.exitCode()).isEqualTo(javaImport.exitCode());
        GoldenComparisonSupport.assertSameRelativeFileBytes(
                GoldenComparisonSupport.relativeFileBytes(pythonProject, "sources"),
                GoldenComparisonSupport.relativeFileBytes(javaProject, "sources"));
        GoldenComparisonSupport.assertSameRelativeFileBytes(
                GoldenComparisonSupport.relativeFileBytes(pythonProject, "images"),
                GoldenComparisonSupport.relativeFileBytes(javaProject, "images"));
        assertThat(GoldenComparisonSupport.normalizeCommandOutput(javaImport.output(), javaProject))
                .isEqualTo(GoldenComparisonSupport.normalizeCommandOutput(pythonImport.output(), pythonProject));
    }

    @Test
    void speakerNotesSplitSuccessMatchesPinnedPython(@TempDir Path tempDir) throws Exception {
        String totalMd =
                """
                # 01_intro
                Intro body unique A.

                # 02_body
                Body content unique B.
                """;

        Path pythonProject = initPythonProject(tempDir.resolve("py-notes"), "notes");
        writeNotesFixtures(pythonProject, totalMd);
        GoldenComparisonSupport.ProcessResult pythonRun = GoldenComparisonSupport.runPython(
                python, totalMdSplitScript, List.of(pythonProject.toString()));

        String date = GoldenComparisonSupport.extractDateFromProjectDir(pythonProject);
        Path javaProject = initJavaProject(tempDir.resolve("java-notes"), "notes", date);
        writeNotesFixtures(javaProject, totalMd);
        EngineResult javaResult = new JavaSpeakerNotesSplitter().split(javaProject, Duration.ofSeconds(60));

        assertThat(pythonRun.exitCode()).isEqualTo(javaResult.exitCode());
        assertThat(GoldenComparisonSupport.normalizeCommandOutput(javaResult.output(), javaProject))
                .isEqualTo(GoldenComparisonSupport.normalizeCommandOutput(pythonRun.output(), pythonProject));
        assertThat(Files.readAllBytes(javaProject.resolve("notes/01_intro.md")))
                .isEqualTo(Files.readAllBytes(pythonProject.resolve("notes/01_intro.md")));
        assertThat(Files.readAllBytes(javaProject.resolve("notes/02_body.md")))
                .isEqualTo(Files.readAllBytes(pythonProject.resolve("notes/02_body.md")));
        assertThat(Files.exists(javaProject.resolve("notes/total.md"))).isTrue();
        assertThat(Files.exists(pythonProject.resolve("notes/total.md"))).isTrue();
    }

    @Test
    void speakerNotesSplitMissingPageMatchesPinnedPython(@TempDir Path tempDir) throws Exception {
        String totalMd =
                """
                # 01_intro
                Only one.
                """;

        Path pythonProject = initPythonProject(tempDir.resolve("py-miss"), "miss");
        writeNotesFixtures(pythonProject, totalMd);
        GoldenComparisonSupport.ProcessResult pythonRun = GoldenComparisonSupport.runPython(
                python, totalMdSplitScript, List.of(pythonProject.toString()));

        String date = GoldenComparisonSupport.extractDateFromProjectDir(pythonProject);
        Path javaProject = initJavaProject(tempDir.resolve("java-miss"), "miss", date);
        writeNotesFixtures(javaProject, totalMd);
        EngineResult javaResult = new JavaSpeakerNotesSplitter().split(javaProject, Duration.ofSeconds(60));

        assertThat(pythonRun.exitCode()).isEqualTo(javaResult.exitCode());
        assertThat(GoldenComparisonSupport.normalizeCommandOutput(javaResult.output(), javaProject))
                .isEqualTo(GoldenComparisonSupport.normalizeCommandOutput(pythonRun.output(), pythonProject));
        assertThat(Files.exists(javaProject.resolve("notes/01_intro.md"))).isFalse();
        assertThat(Files.exists(javaProject.resolve("notes/02_body.md"))).isFalse();
        assertThat(Files.exists(pythonProject.resolve("notes/01_intro.md"))).isFalse();
        assertThat(Files.exists(pythonProject.resolve("notes/02_body.md"))).isFalse();
    }

    @Test
    void svgQualityForbiddenStyleExitMatchesPinnedPython(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("style-gate");
        Path svgDir = project.resolve("svg_output");
        Files.createDirectories(svgDir);
        Files.writeString(
                svgDir.resolve("slide_01.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\">"
                        + "<style>.a{}</style></svg>",
                StandardCharsets.UTF_8);

        GoldenComparisonSupport.ProcessResult pythonRun = GoldenComparisonSupport.runPython(
                python,
                svgQualityCheckerScript,
                List.of(project.toString(), "--format", "ppt169"));
        EngineResult javaResult = new JavaSvgQualityChecker().check(project, "ppt169", Duration.ofSeconds(60));

        assertThat(pythonRun.timedOut()).isFalse();
        assertThat(javaResult.timedOut()).isFalse();
        assertThat(pythonRun.exitCode()).isEqualTo(1);
        assertThat(javaResult.exitCode()).isEqualTo(1);
        assertThat(pythonRun.output()).contains("Detected forbidden <style> element");
        assertThat(javaResult.output()).contains("Detected forbidden <style> element");
    }

    @Test
    void svgQualityViewBoxMismatchExitMatchesPinnedPython(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("viewbox-gate");
        Path svgDir = project.resolve("svg_output");
        Files.createDirectories(svgDir);
        Files.writeString(
                svgDir.resolve("slide_01.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1024 768\"></svg>",
                StandardCharsets.UTF_8);

        GoldenComparisonSupport.ProcessResult pythonRun = GoldenComparisonSupport.runPython(
                python,
                svgQualityCheckerScript,
                List.of(project.toString(), "--format", "ppt169"));
        EngineResult javaResult = new JavaSvgQualityChecker().check(project, "ppt169", Duration.ofSeconds(60));

        assertThat(pythonRun.timedOut()).isFalse();
        assertThat(javaResult.timedOut()).isFalse();
        assertThat(pythonRun.exitCode()).isEqualTo(1);
        assertThat(javaResult.exitCode()).isEqualTo(1);
        assertThat(pythonRun.output()).contains("viewBox mismatch");
        assertThat(javaResult.output()).contains("viewBox mismatch");
    }

    @Test
    void svgQualityLegacyAttrExitMatchesPinnedPython(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("legacy-gate");
        Path svgDir = project.resolve("svg_output");
        Files.createDirectories(svgDir);
        Files.writeString(
                svgDir.resolve("slide_01.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\">"
                        + "<rect data-pptx-module-bounds=\"0 0 10 10\" width=\"10\" height=\"10\"/>"
                        + "</svg>",
                StandardCharsets.UTF_8);

        GoldenComparisonSupport.ProcessResult pythonRun = GoldenComparisonSupport.runPython(
                python,
                svgQualityCheckerScript,
                List.of(project.toString(), "--format", "ppt169"));
        EngineResult javaResult = new JavaSvgQualityChecker().check(project, "ppt169", Duration.ofSeconds(60));

        assertThat(pythonRun.exitCode()).isEqualTo(1);
        assertThat(javaResult.exitCode()).isEqualTo(1);
        assertThat(pythonRun.output()).contains("legacy");
        assertThat(javaResult.output()).contains("legacy data-pptx-module-bounds");
    }

    @Test
    void svgQualityBadOpacityExitMatchesPinnedPython(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("opacity-gate");
        Path svgDir = project.resolve("svg_output");
        Files.createDirectories(svgDir);
        Files.writeString(
                svgDir.resolve("slide_01.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\">"
                        + "<rect opacity=\"12px\" width=\"10\" height=\"10\"/>"
                        + "</svg>",
                StandardCharsets.UTF_8);

        GoldenComparisonSupport.ProcessResult pythonRun = GoldenComparisonSupport.runPython(
                python,
                svgQualityCheckerScript,
                List.of(project.toString(), "--format", "ppt169"));
        EngineResult javaResult = new JavaSvgQualityChecker().check(project, "ppt169", Duration.ofSeconds(60));

        assertThat(pythonRun.exitCode()).isEqualTo(1);
        assertThat(javaResult.exitCode()).isEqualTo(1);
        assertThat(javaResult.output()).contains("opacity=");
    }

    @Test
    void svgQualityUnsupportedTagExitMatchesPinnedPython(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("mesh-gate");
        Path svgDir = project.resolve("svg_output");
        Files.createDirectories(svgDir);
        Files.writeString(
                svgDir.resolve("slide_01.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\">"
                        + "<mesh id=\"bad\"/>"
                        + "</svg>",
                StandardCharsets.UTF_8);

        GoldenComparisonSupport.ProcessResult pythonRun = GoldenComparisonSupport.runPython(
                python,
                svgQualityCheckerScript,
                List.of(project.toString(), "--format", "ppt169"));
        EngineResult javaResult = new JavaSvgQualityChecker().check(project, "ppt169", Duration.ofSeconds(60));

        assertThat(pythonRun.exitCode()).isEqualTo(1);
        assertThat(javaResult.exitCode()).isEqualTo(1);
        assertThat(javaResult.output()).contains("Unsupported visual SVG element");
    }

    private Path initPythonProject(Path projectsDir, String name) throws Exception {
        Files.createDirectories(projectsDir);
        GoldenComparisonSupport.ProcessResult init = GoldenComparisonSupport.runPython(
                python,
                projectManagerScript,
                List.of("init", name, "--format", "ppt169", "--dir", projectsDir.toString()));
        assertThat(init.exitCode()).isZero();
        return GoldenComparisonSupport.parseProjectCreatedPath(init.output());
    }

    private Path initJavaProject(Path projectsDir, String name, String date) {
        JavaProjectWorkspace workspace =
                new JavaProjectWorkspace(GoldenComparisonSupport.clockAtLocalNoon(date));
        EngineResult init = workspace.init(name, "ppt169", projectsDir, Duration.ofSeconds(60));
        assertThat(init.successful()).isTrue();
        return projectsDir.resolve(name + "_ppt169_" + date);
    }

    private static void writeNotesFixtures(Path project, String totalMd) throws Exception {
        Path svgDir = project.resolve("svg_output");
        Files.createDirectories(svgDir);
        Files.writeString(
                svgDir.resolve("01_intro.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>",
                StandardCharsets.UTF_8);
        Files.writeString(
                svgDir.resolve("02_body.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>",
                StandardCharsets.UTF_8);
        Path notesDir = project.resolve("notes");
        Files.createDirectories(notesDir);
        Files.writeString(notesDir.resolve("total.md"), totalMd.strip() + "\n", StandardCharsets.UTF_8);
    }
}
