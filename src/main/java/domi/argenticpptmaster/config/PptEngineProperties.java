package domi.argenticpptmaster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Capability-engine dual-run settings under {@code ppt-master.engine.*}.
 *
 * @param dualRunEnabled         when true, dual-run secondary exporters when wired
 * @param dualRunFailOnMismatch  when true, mismatch throws; otherwise warn-only
 * @param importPythonDualRun    when true, after Java-authoritative import conversion,
 *                               optionally run Python import diagnostics (never overrides Java)
 */
@ConfigurationProperties(prefix = "ppt-master.engine")
public record PptEngineProperties(
        Boolean dualRunEnabled,
        Boolean dualRunFailOnMismatch,
        Boolean importPythonDualRun) {

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
    }

    public static PptEngineProperties defaults() {
        return new PptEngineProperties(false, false, false);
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
}
