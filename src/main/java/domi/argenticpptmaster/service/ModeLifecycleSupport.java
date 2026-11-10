package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobStatus;
import java.time.Duration;
import java.util.Map;

/** 生命周期共享实现所需的模式差异。 */
public interface ModeLifecycleSupport {

    String schemaVersion();

    String ownershipMissingMessage();

    Map<String, Integer> defaultArtifactCounts();

    Map<String, Integer> countArtifacts(PptJob job);

    Duration retentionForTerminalStatus(PptJobStatus status);
}
