package domi.argenticpptmaster.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import domi.argenticpptmaster.config.PptEngineProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoutingProjectWorkspaceTests {

    @Mock
    private ProjectWorkspace javaWorkspace;

    @Mock
    private ProjectWorkspace pythonWorkspace;

    @TempDir
    Path tempDir;

    @Test
    void routesAllSourcesIncludingUrlAndPandocToJavaOnly() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Path pptx = tempDir.resolve("deck.pptx");
        Path odt = tempDir.resolve("notes.odt");
        Path url = Path.of("https://example.com/article");
        Files.writeString(pptx, "pk");
        Files.writeString(odt, "odt");

        when(javaWorkspace.importSources(eq(project), anyList(), eq(true), any()))
                .thenReturn(new EngineResult(0, "[OK] java", false));

        RoutingProjectWorkspace routing =
                new RoutingProjectWorkspace(javaWorkspace, pythonWorkspace, PptEngineProperties.defaults());
        EngineResult result =
                routing.importSources(project, List.of(pptx, odt, url), true, Duration.ofSeconds(5));

        assertThat(result.successful()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Path>> javaSources = ArgumentCaptor.forClass(List.class);
        verify(javaWorkspace).importSources(eq(project), javaSources.capture(), eq(true), any());
        assertThat(javaSources.getValue()).containsExactly(pptx, odt, url);
        verify(pythonWorkspace, never()).importSources(any(), anyList(), anyBoolean(), any());
    }

    @Test
    void doesNotRouteEpubToPython() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Path epub = tempDir.resolve("book.epub");
        Files.writeString(epub, "epub");

        when(javaWorkspace.importSources(eq(project), anyList(), eq(true), any()))
                .thenReturn(new EngineResult(1, "[ERROR] epub unsupported", false));

        RoutingProjectWorkspace routing =
                new RoutingProjectWorkspace(javaWorkspace, pythonWorkspace, PptEngineProperties.defaults());
        EngineResult result = routing.importSources(project, List.of(epub), true, Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(1);
        verify(javaWorkspace).importSources(eq(project), eq(List.of(epub)), eq(true), any());
        verify(pythonWorkspace, never()).importSources(any(), anyList(), anyBoolean(), any());
    }

    @Test
    void abortsWithoutPythonWhenJavaFails() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Path pptx = tempDir.resolve("deck.pptx");
        Path odt = tempDir.resolve("notes.odt");
        Files.writeString(pptx, "pk");
        Files.writeString(odt, "odt");

        when(javaWorkspace.importSources(eq(project), anyList(), eq(true), any()))
                .thenReturn(new EngineResult(1, "[ERROR] boom", false));

        RoutingProjectWorkspace routing =
                new RoutingProjectWorkspace(javaWorkspace, pythonWorkspace, PptEngineProperties.defaults());
        EngineResult result =
                routing.importSources(project, List.of(pptx, odt), true, Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("[ERROR] boom");
        verify(pythonWorkspace, never()).importSources(any(), anyList(), anyBoolean(), any());
    }

    @Test
    void routesLegacyPptToJavaOnly() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Path ppt = tempDir.resolve("legacy.ppt");
        Files.writeString(ppt, "ppt");

        when(javaWorkspace.importSources(eq(project), anyList(), eq(true), any()))
                .thenReturn(new EngineResult(0, "[OK] java", false));

        RoutingProjectWorkspace routing =
                new RoutingProjectWorkspace(javaWorkspace, pythonWorkspace, PptEngineProperties.defaults());
        EngineResult result = routing.importSources(project, List.of(ppt), true, Duration.ofSeconds(5));

        assertThat(result.successful()).isTrue();
        verify(pythonWorkspace, never()).importSources(any(), anyList(), anyBoolean(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Path>> javaSources = ArgumentCaptor.forClass(List.class);
        verify(javaWorkspace).importSources(eq(project), javaSources.capture(), eq(true), any());
        assertThat(javaSources.getValue()).containsExactly(ppt);
    }

    @Test
    void dualRunWritesToSidecarNotAuthoritySources() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("sources"));
        Path authorityMd = project.resolve("sources/paper.md");
        Files.writeString(authorityMd, "authority");
        Path pdf = tempDir.resolve("paper.pdf");
        Files.writeString(pdf, "%PDF");

        when(javaWorkspace.importSources(eq(project), anyList(), eq(true), any()))
                .thenReturn(new EngineResult(0, "[OK] java", false));
        when(pythonWorkspace.importSources(any(), anyList(), eq(true), any()))
                .thenAnswer(invocation -> {
                    Path dualProject = invocation.getArgument(0);
                    assertThat(dualProject).isEqualByComparingTo(project.resolve("sources/.dual-run"));
                    Files.createDirectories(dualProject.resolve("sources"));
                    Files.writeString(dualProject.resolve("sources/paper.md"), "python-dual");
                    return new EngineResult(1, "[WARN] dual", false);
                });

        PptEngineProperties props = new PptEngineProperties(false, false, true);
        RoutingProjectWorkspace routing =
                new RoutingProjectWorkspace(javaWorkspace, pythonWorkspace, props);
        EngineResult result = routing.importSources(project, List.of(pdf), true, Duration.ofSeconds(5));

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).contains("[OK] java");
        assertThat(Files.readString(authorityMd)).isEqualTo("authority");
        assertThat(project.resolve("sources/.dual-run/sources/paper.md")).exists();
        verify(pythonWorkspace)
                .importSources(eq(project.resolve("sources/.dual-run")), eq(List.of(pdf)), eq(true), any());
    }
}
