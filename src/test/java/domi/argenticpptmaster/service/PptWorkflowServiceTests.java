package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobStatus;
import domi.argenticpptmaster.exception.PptJobStateException;
import domi.argenticpptmaster.agent.AgentScopeWorkflowAgent;
import domi.argenticpptmaster.agent.AgentScopeWorkflowAgentFactory;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;

@SpringBootTest
class PptWorkflowServiceTests {

    @TempDir
    static Path tempDir;

    @Autowired
    private PptWorkflowService workflowService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ppt-master.workspace-path", () -> tempDir.resolve("workspace").toString());
        registry.add("ppt-master.repo-path", () -> tempDir.resolve("repo").toString());
    }

    @BeforeEach
    void setUpPptMasterScript() throws IOException {
        Path script = tempDir.resolve("repo/skills/ppt-master/scripts/project_manager.py");
        Files.createDirectories(script.getParent());
        Files.writeString(script, """
                #!/usr/bin/env python3
                import argparse
                from datetime import datetime
                from pathlib import Path

                parser = argparse.ArgumentParser()
                parser.add_argument("command")
                parser.add_argument("project_name")
                parser.add_argument("--format", default="ppt169")
                parser.add_argument("--dir", required=True)
                args = parser.parse_args()

                if args.command != "init":
                    raise SystemExit(2)

                date = datetime.now().strftime("%Y%m%d")
                project = Path(args.dir) / f"{args.project_name}_{args.format}_{date}"
                for name in ("svg_output", "svg_final", "images", "icons", "notes", "templates", "sources", "analysis", "exports"):
                    (project / name).mkdir(parents=True, exist_ok=True)
                """);
    }

    @Test
    void createsJobAndStoresSource() {
        MockMultipartFile source = new MockMultipartFile(
                "files",
                "source.md",
                MediaType.TEXT_MARKDOWN_VALUE,
                "# Title".getBytes());

        PptJob job = workflowService.createJob(
                java.util.List.of(source),
                "demo",
                "ppt169",
                "make a concise business deck");

        assertThat(job.projectName()).isEqualTo("demo");
        assertThat(job.format()).isEqualTo("ppt169");
        assertThat(job.sourceFiles()).hasSize(1);
        assertThat(Files.exists(job.sourceFiles().get(0).storedPath())).isTrue();
        assertThat(job.status()).isIn(PptJobStatus.ACCEPTED, PptJobStatus.PREPARING, PptJobStatus.WAITING_CONFIRMATION);
    }

    @Test
    void rejectsUnsupportedSourceExtension() {
        MockMultipartFile source = new MockMultipartFile(
                "files",
                "source.exe",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                "bad".getBytes());

        assertThatThrownBy(() -> workflowService.createJob(java.util.List.of(source), "demo", "ppt169", null))
                .isInstanceOf(PptJobStateException.class)
                .hasMessage("unsupported source file extension: exe");
    }

    @Test
    void rejectsUnsupportedCanvasFormat() {
        MockMultipartFile source = new MockMultipartFile(
                "files",
                "source.md",
                MediaType.TEXT_MARKDOWN_VALUE,
                "# Title".getBytes());

        assertThatThrownBy(() -> workflowService.createJob(java.util.List.of(source), "demo", "../bad", null))
                .isInstanceOf(PptJobStateException.class)
                .hasMessage("unsupported canvas format: ../bad");
    }

    @TestConfiguration
    static class TestAgentConfig {

        @Bean
        @Primary
        AgentScopeWorkflowAgentFactory agentFactory() {
            return job -> new AgentScopeWorkflowAgent() {
                @Override
                public Flux<AgentEvent> streamEvents(List<Msg> messages, RuntimeContext runtimeContext) {
                    return Flux.just(
                            new AgentStartEvent("reply-1", runtimeContext.getSessionId(), "test-agent"),
                            new RequireExternalExecutionEvent(
                                    "reply-1",
                                    List.of(new ToolUseBlock(
                                            "call-1",
                                            "request_plan_confirmation",
                                            Map.of(
                                                    "planSummary", "import and inspect sources",
                                                    "pendingSteps", "wait for operator approval")))));
                }
            };
        }
    }
}
