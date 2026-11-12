package domi.argenticpptmaster.engine.python;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.NotesAudioGateway;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Legacy {@code notes_to_audio.py} adapter.
 *
 * <p>保留源文件供日后清扫；生产 Bean 已切换为 Java Fish，本类不再装配。</p>
 */
public final class PythonNotesAudioGateway implements NotesAudioGateway {

    public static final String SCRIPT = "skills/ppt-master/scripts/notes_to_audio.py";

    private final PptMasterCommandExecutor commands;

    public PythonNotesAudioGateway(PptMasterCommandExecutor commands) {
        this.commands = commands;
    }

    @Override
    public EngineResult synthesize(Path project, String voice, Duration timeout) {
        List<String> arguments = List.of(
                project.toString(),
                "--voice",
                voice == null || voice.isBlank() ? "zh-CN-YunjianNeural" : voice,
                "--rate",
                "+0%",
                "--provider",
                "edge");
        return PythonEngineSupport.run(commands, SCRIPT, arguments, timeout);
    }
}
