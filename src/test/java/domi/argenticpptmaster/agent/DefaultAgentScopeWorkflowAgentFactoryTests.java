package domi.argenticpptmaster.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.AgentScopeProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.service.PptJobEventPublisher;
import domi.argenticpptmaster.service.PptWorkflowEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultAgentScopeWorkflowAgentFactoryTests {

    @TempDir
    Path tempDir;

    @Test
    void collectSourceMarkdownReadsNormalizedMarkdownFilesOnly() throws IOException {
        TestContext context = createContext();
        Files.writeString(context.projectPath.resolve("sources/alpha.md"), "# Alpha\nA");
        Files.writeString(context.projectPath.resolve("sources/beta.markdown"), "# Beta\nB");
        Files.writeString(context.projectPath.resolve("sources/raw.pdf"), "binary");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = context.tools.collectSourceMarkdown(null, 100, context.runtime);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> markdownFiles = (List<Map<String, Object>>) result.get("markdownFiles");

        assertThat(result).containsEntry("count", 2);
        assertThat(markdownFiles).extracting(file -> file.get("relativePath"))
                .containsExactly("sources/alpha.md", "sources/beta.markdown");
        assertThat(markdownFiles).extracting(file -> file.get("content"))
                .allSatisfy(content -> assertThat((String) content).startsWith("# "));
    }

    @Test
    void writeProjectTextFileWritesAllowedPathsAndRejectsOthers() throws IOException {
        TestContext context = createContext();

        Map<String, Object> writeResult = context.tools.writeProjectTextFile(
                "notes/total.md",
                "# 01_cover\nhello",
                context.runtime);

        assertThat(writeResult).containsEntry("relativePath", "notes/total.md");
        assertThat(Files.readString(context.projectPath.resolve("notes/total.md"))).isEqualTo("# 01_cover\nhello");
        assertThatThrownBy(() -> context.tools.writeProjectTextFile("exports/filled.pptx", "x", context.runtime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void postProcessingToolsDelegateToExpectedScripts() throws IOException {
        TestContext context = createContext();
        Files.writeString(context.projectPath.resolve("notes/total.md"), "# 01_cover\nhello");

        context.tools.validateSvgOutput(context.runtime);
        context.tools.splitSpeakerNotes(context.runtime);
        context.tools.finalizeProjectSvg(context.runtime);

        assertThat(context.executor.calledScripts()).containsExactly(
                "skills/ppt-master/scripts/svg_quality_checker.py",
                "skills/ppt-master/scripts/total_md_split.py",
                "skills/ppt-master/scripts/finalize_svg.py");
        assertThat(context.executor.argumentsFor("skills/ppt-master/scripts/svg_quality_checker.py"))
                .containsExactly(context.projectPath.toString(), "--format", "ppt169");
    }

    private TestContext createContext() throws IOException {
        Path repoPath = tempDir.resolve("repo");
        Path workspacePath = tempDir.resolve("workspace");
        Path projectPath = workspacePath.resolve("projects/demo_ppt169_20260702");
        Files.createDirectories(projectPath.resolve("sources"));
        Files.createDirectories(projectPath.resolve("analysis"));
        Files.createDirectories(projectPath.resolve("notes"));
        Files.createDirectories(projectPath.resolve("svg_output"));
        Files.createDirectories(projectPath.resolve("svg_final"));
        Files.createDirectories(projectPath.resolve("exports"));

        PptMasterProperties properties = new PptMasterProperties(repoPath, workspacePath, "python3", Duration.ofMinutes(1));
        AgentScopeProperties agentProperties = new AgentScopeProperties(
                "openai",
                "dummy-model",
                null,
                null,
                8,
                tempDir.resolve("sessions"),
                "ppt-master-service");
        RecordingCommandExecutor executor = new RecordingCommandExecutor(properties);
        PptWorkflowEvents events = new PptWorkflowEvents(new PptJobEventPublisher());
        DefaultAgentScopeWorkflowAgentFactory factory = new DefaultAgentScopeWorkflowAgentFactory(
                agentProperties,
                properties,
                executor,
                events);
        DefaultAgentScopeWorkflowAgentFactory.PptAgentTools tools = factory.new PptAgentTools();

        PptJob job = new PptJob(UUID.randomUUID(), "demo", "ppt169", "make a deck", workspacePath.resolve("jobs/demo"));
        job.prepareProject(projectPath);
        DefaultAgentScopeWorkflowAgentFactory.PptAgentToolRuntime runtime =
                new DefaultAgentScopeWorkflowAgentFactory.PptAgentToolRuntime(job, properties, executor, events);
        return new TestContext(projectPath, tools, runtime, executor);
    }

    private record TestContext(
            Path projectPath,
            DefaultAgentScopeWorkflowAgentFactory.PptAgentTools tools,
            DefaultAgentScopeWorkflowAgentFactory.PptAgentToolRuntime runtime,
            RecordingCommandExecutor executor) {
    }

    private static final class RecordingCommandExecutor extends PptMasterCommandExecutor {

        private final List<String> calledScripts = new ArrayList<>();
        private final java.util.LinkedHashMap<String, List<String>> argumentsByScript = new java.util.LinkedHashMap<>();

        RecordingCommandExecutor(PptMasterProperties properties) {
            super(properties);
        }

        @Override
        public CommandResult runPythonScript(String scriptRelativePath, List<String> arguments) {
            calledScripts.add(scriptRelativePath);
            argumentsByScript.put(scriptRelativePath, List.copyOf(arguments));
            return new CommandResult(0, "ok", false);
        }

        List<String> calledScripts() {
            return calledScripts;
        }

        List<String> argumentsFor(String scriptRelativePath) {
            return argumentsByScript.get(scriptRelativePath);
        }
    }
}
