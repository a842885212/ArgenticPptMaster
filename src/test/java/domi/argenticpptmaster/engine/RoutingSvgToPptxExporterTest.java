// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import domi.argenticpptmaster.config.PptEngineProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutingSvgToPptxExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void javaAuthorityReturnedWhenDualRunDisabled() {
        SvgToPptxExporter javaEngine = mock(SvgToPptxExporter.class);
        SvgToPptxExporter pythonEngine = mock(SvgToPptxExporter.class);
        when(javaEngine.export(any(), any())).thenReturn(new EngineResult(0, "java-ok", false));

        RoutingSvgToPptxExporter routing =
                new RoutingSvgToPptxExporter(javaEngine, pythonEngine, PptEngineProperties.defaults());
        EngineResult result = routing.export(tempDir, Duration.ZERO);

        assertThat(result.output()).isEqualTo("java-ok");
        verify(pythonEngine, never()).export(any(), any());
    }

    @Test
    void dualRunInvokesPythonDiagnostically() {
        SvgToPptxExporter javaEngine = mock(SvgToPptxExporter.class);
        SvgToPptxExporter pythonEngine = mock(SvgToPptxExporter.class);
        when(javaEngine.export(any(), any())).thenReturn(new EngineResult(0, "java-ok", false));
        when(pythonEngine.export(any(), any())).thenReturn(new EngineResult(0, "python-ok", false));

        RoutingSvgToPptxExporter routing = new RoutingSvgToPptxExporter(
                javaEngine, pythonEngine, new PptEngineProperties(true, false, false));
        EngineResult result = routing.export(tempDir, Duration.ZERO);

        assertThat(result.output()).isEqualTo("java-ok");
        verify(pythonEngine).export(any(), any());
    }
}
