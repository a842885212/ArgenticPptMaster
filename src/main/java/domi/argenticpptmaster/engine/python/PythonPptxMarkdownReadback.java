package domi.argenticpptmaster.engine.python;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.PptxMarkdownReadback;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class PythonPptxMarkdownReadback implements PptxMarkdownReadback {

    public static final String BEAUTIFY_SCRIPT = "skills/ppt-master/scripts/ppt_to_md.py";
    public static final String NATIVE_ENHANCE_SCRIPT = "skills/ppt-master/scripts/source_to_md/ppt_to_md.py";

    private final PptMasterCommandExecutor commands;
    private final String scriptRelativePath;

    public PythonPptxMarkdownReadback(PptMasterCommandExecutor commands, String scriptRelativePath) {
        this.commands = commands;
        this.scriptRelativePath = Objects.requireNonNull(scriptRelativePath, "scriptRelativePath");
    }

    public String scriptRelativePath() {
        return scriptRelativePath;
    }

    @Override
    public EngineResult toMarkdown(Path pptx, Path markdownOut, Duration timeout) {
        return PythonEngineSupport.run(commands, scriptRelativePath, List.of(
                pptx.toString(), "-o", markdownOut.toString()), timeout);
    }
}
