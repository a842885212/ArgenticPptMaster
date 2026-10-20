package domi.argenticpptmaster.engine;

/**
 * Capability-engine command result.
 *
 * <p>Mirrors {@link domi.argenticpptmaster.infra.PptMasterCommandExecutor.CommandResult}
 * so callers no longer depend on the Python process runner.</p>
 */
public record EngineResult(int exitCode, String output, boolean timedOut) {

    public static EngineResult from(domi.argenticpptmaster.infra.PptMasterCommandExecutor.CommandResult result) {
        return new EngineResult(result.exitCode(), result.output(), result.timedOut());
    }

    public boolean successful() {
        return exitCode == 0 && !timedOut;
    }
}
