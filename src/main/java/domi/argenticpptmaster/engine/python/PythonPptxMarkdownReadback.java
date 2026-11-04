package domi.argenticpptmaster.engine.python;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.PptxMarkdownReadback;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class PythonPptxMarkdownReadback implements PptxMarkdownReadback {

    /**
     * ppt_to_md.py 唯一有效路径：ppt-master c4823393 已将脚本移入 source_to_md/ 子包，
     * 顶层 scripts/ppt_to_md.py 路径已失效。保留旧常量名作为 deprecated alias，去 Python 化时清理。
     */
    public static final String SCRIPT = "skills/ppt-master/scripts/source_to_md/ppt_to_md.py";
    /** @deprecated 使用 {@link #SCRIPT}（同一脚本，路径已修正）。 */
    @Deprecated
    public static final String BEAUTIFY_SCRIPT = SCRIPT;
    /** @deprecated 使用 {@link #SCRIPT}（同一脚本，路径已修正）。 */
    @Deprecated
    public static final String NATIVE_ENHANCE_SCRIPT = SCRIPT;

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
