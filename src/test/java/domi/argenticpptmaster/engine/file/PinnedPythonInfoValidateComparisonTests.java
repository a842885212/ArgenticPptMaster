package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import domi.argenticpptmaster.engine.EngineResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parity tests: Java-authoritative {@code info} / {@code validate} output must match the
 * pinned Python {@code project_manager.py} byte-for-byte on identical project fixtures.
 * Skipped when the pinned Python environment is unavailable.
 */
class PinnedPythonInfoValidateComparisonTests {

    private Path python;
    private Path projectManagerScript;

    private final JavaProjectWorkspace javaWorkspace = new JavaProjectWorkspace();

    @TempDir
    Path tempDir;

    @BeforeEach
    void assumePinnedEnvironment() {
        Path pinRoot = GoldenComparisonSupport.pinRoot();
        python = GoldenComparisonSupport.pythonExecutable();
        projectManagerScript = GoldenComparisonSupport.projectManagerScript(pinRoot);
        assumeTrue(GoldenComparisonSupport.goldenEnvironmentReady(pinRoot, python, projectManagerScript));
    }

    @Test
    void infoMatchesPythonOnCompleteProject() throws Exception {
        Path project = completeProject("demo_ppt169_20261103");

        assertParity("info", project);
    }

    @Test
    void validateMatchesPythonOnCompleteProject() throws Exception {
        Path project = completeProject("demo_ppt169_20261103");

        assertParity("validate", project);
    }

    @Test
    void validateMatchesPythonWhenReadmeMissing() throws Exception {
        Path project = projectDir("demo_ppt169_20261103");
        write(project, "design_spec.md", "spec");
        write(project, "svg_output/slide_1_main.svg", svg("0 0 1280 720"));

        assertParity("validate", project);
    }

    @Test
    void infoAndValidateMatchPythonOnBareDirectoryName() throws Exception {
        Path project = projectDir("demo");
        write(project, "README.md", "# demo");
        write(project, "design_spec.md", "spec");
        write(project, "svg_output/slide_1_main.svg", svg("0 0 640 640"));

        assertParity("info", project);
        assertParity("validate", project);
    }

    @Test
    void infoMatchesPythonOnMissingProject() throws Exception {
        Path project = tempDir.resolve("ghost_ppt169_20261103");

        assertParity("info", project);
    }

    @Test
    void validateMatchesPythonOnMissingProject() throws Exception {
        Path project = tempDir.resolve("ghost_ppt169_20261103");

        assertParity("validate", project);
    }

    // ---------- helpers ----------

    private void assertParity(String command, Path project) throws Exception {
        GoldenComparisonSupport.ProcessResult pythonRun = GoldenComparisonSupport.runPython(
                python, projectManagerScript, List.of(command, project.toString()));

        EngineResult javaResult = "info".equals(command)
                ? javaWorkspace.info(project, Duration.ofSeconds(60))
                : javaWorkspace.validate(project, Duration.ofSeconds(60));

        assertThat(javaResult.exitCode())
                .as("%s exit code for %s", command, project)
                .isEqualTo(pythonRun.exitCode());
        assertThat(javaResult.output())
                .as("%s output for %s", command, project)
                .isEqualTo(pythonRun.output());
    }

    private Path completeProject(String dirName) throws Exception {
        Path project = projectDir(dirName);
        write(project, "README.md", "# demo");
        write(project, "design_spec.md", "spec");
        write(project, "sources/a.md", "a");
        write(project, "sources/b.md", "b");
        write(project, "svg_output/slide_1_main.svg", svg("0 0 1280 720"));
        write(project, "svg_output/slide_2_main.svg", svg("0 0 1280 720"));
        return project;
    }

    private Path projectDir(String dirName) throws Exception {
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
