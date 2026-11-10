package domi.argenticpptmaster.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/** 清理扫描所需的模式差异。 */
public interface ModeCleanupSupport {

    boolean cleanupDryRunEnabled();

    boolean cleanupDeletionEnabled();

    Path jobsRoot();

    Path recycleRoot();

    ModeLifecycleStore lifecycleStore();

    void auditCleanup(String action, UUID jobId, String reasonCode, boolean dryRun);

    void recordCleanupSuccess(Duration elapsed);

    void recordCleanupFailure(Duration elapsed);

    String scanFailedLogMessage();

    String recycleScanFailedLogMessage();
}
