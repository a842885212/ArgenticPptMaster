package domi.argenticpptmaster.engine.python;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class PythonProjectWorkspaceTests {

    @TempDir
    Path tempDir;

    private PptMasterCommandExecutor commands;
    private PythonProjectWorkspace workspace;

    @BeforeEach
    void setUp() {
        commands = mock(PptMasterCommandExecutor.class);
        when(commands.runPythonScript(any(), any(), any(Duration.class)))
                .thenReturn(new PptMasterCommandExecutor.CommandResult(0, "ok", false));
        workspace = new PythonProjectWorkspace(commands);
    }

    @Test
    void initInvokesProjectManagerScriptWithExpectedArgs() {
        Path projectsDir = tempDir.resolve("projects");
        Duration timeout = Duration.ofSeconds(30);

        EngineResult result = workspace.init("demo", "ppt169", projectsDir, timeout);

        assertThat(result.successful()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(commands).runPythonScript(
                eq(PythonProjectWorkspace.SCRIPT), argsCaptor.capture(), eq(timeout));
        assertThat(PythonProjectWorkspace.SCRIPT)
                .isEqualTo("skills/ppt-master/scripts/project_manager.py");
        assertThat(argsCaptor.getValue()).containsExactly(
                "init", "demo", "--format", "ppt169", "--dir", projectsDir.toString());
    }

    @Test
    void importSourcesInvokesProjectManagerScriptWithPathsAndCopyFlag() {
        Path project = tempDir.resolve("project");
        Path sourceA = tempDir.resolve("a.md");
        Path sourceB = tempDir.resolve("b.pptx");
        Duration timeout = Duration.ofSeconds(15);

        EngineResult result = workspace.importSources(project, List.of(sourceA, sourceB), true, timeout);

        assertThat(result.successful()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(commands).runPythonScript(
                eq(PythonProjectWorkspace.SCRIPT), argsCaptor.capture(), eq(timeout));
        assertThat(argsCaptor.getValue()).containsExactly(
                "import-sources",
                project.toString(),
                sourceA.toString(),
                sourceB.toString(),
                "--copy");
    }

    @Test
    void importSourcesOmitsCopyFlagWhenNotRequested() {
        Path project = tempDir.resolve("project");
        Path source = tempDir.resolve("source.md");
        Duration timeout = Duration.ofSeconds(10);

        workspace.importSources(project, List.of(source), false, timeout);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(commands).runPythonScript(
                eq(PythonProjectWorkspace.SCRIPT), argsCaptor.capture(), eq(timeout));
        assertThat(argsCaptor.getValue()).containsExactly(
                "import-sources", project.toString(), source.toString());
        assertThat(argsCaptor.getValue()).doesNotContain("--copy");
    }
}
