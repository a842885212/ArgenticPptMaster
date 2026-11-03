package domi.argenticpptmaster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Capability-engine dual-run settings under {@code ppt-master.engine.*}.
 *
 * <p>Pandoc binary path is also exposed via top-level {@code ppt-master.pandoc-command}
 * (preferred when set in {@code application.properties}); this field is the engine-bound
 * fallback ({@code ppt-master.engine.pandoc-command}).
 *
 * @param dualRunEnabled         when true, dual-run secondary exporters when wired
 * @param dualRunFailOnMismatch  when true, mismatch throws; otherwise warn-only
 * @param importPythonDualRun    when true, after Java-authoritative import conversion,
 *                               optionally run Python import diagnostics (never overrides Java)
 * @param pandocCommand          pandoc executable (default {@code pandoc})
 */
@ConfigurationProperties(prefix = "ppt-master.engine")
public record PptEngineProperties(
        Boolean dualRunEnabled,
        Boolean dualRunFailOnMismatch,
        Boolean importPythonDualRun,
        String pandocCommand) {

    @ConstructorBinding
    public PptEngineProperties {
        if (dualRunEnabled == null) {
            dualRunEnabled = false;
        }
        if (dualRunFailOnMismatch == null) {
            dualRunFailOnMismatch = false;
        }
        if (importPythonDualRun == null) {
            importPythonDualRun = false;
        }
        if (pandocCommand == null || pandocCommand.isBlank()) {
            pandocCommand = "pandoc";
        }
    }

    /** Backward-compatible 3-arg constructor (pandoc defaults to {@code pandoc}). */
    public PptEngineProperties(
            Boolean dualRunEnabled, Boolean dualRunFailOnMismatch, Boolean importPythonDualRun) {
        this(dualRunEnabled, dualRunFailOnMismatch, importPythonDualRun, "pandoc");
    }

    public static PptEngineProperties defaults() {
        return new PptEngineProperties(false, false, false, "pandoc");
    }

    public boolean isDualRunEnabled() {
        return Boolean.TRUE.equals(dualRunEnabled);
    }

    public boolean isDualRunFailOnMismatch() {
        return Boolean.TRUE.equals(dualRunFailOnMismatch);
    }

    public boolean isImportPythonDualRun() {
        return Boolean.TRUE.equals(importPythonDualRun);
    }

    public String resolvedPandocCommand() {
        return pandocCommand == null || pandocCommand.isBlank() ? "pandoc" : pandocCommand;
    }
}
