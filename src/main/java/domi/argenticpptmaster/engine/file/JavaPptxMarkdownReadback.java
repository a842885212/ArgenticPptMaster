package domi.argenticpptmaster.engine.file;

import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.PptxMarkdownReadback;
import domi.argenticpptmaster.engine.file.importconvert.ImportConversionException;
import domi.argenticpptmaster.engine.file.importconvert.PptxToMarkdownConverter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * 进程内 {@link PptxMarkdownReadback} 权威实现：委托 {@link PptxToMarkdownConverter}，
 * 输出与 {@code source_to_md/ppt_to_md.py} 对齐（含超链接、chart 转录、image manifest）。
 *
 * <p>进程内转换无子进程语义：所有异常映射为 unsuccessful {@link EngineResult}（exitCode=1、
 * 非超时），使 {@code BeautifyPptxOutputVerifier.runReadback} 走既有 PROCESS_EXIT 失败分支。</p>
 */
public final class JavaPptxMarkdownReadback implements PptxMarkdownReadback {

    @Override
    public EngineResult toMarkdown(Path pptx, Path markdownOut, Duration timeout) {
        Objects.requireNonNull(pptx, "pptx");
        Objects.requireNonNull(markdownOut, "markdownOut");
        try {
            PptxToMarkdownConverter.convert(pptx, markdownOut);
            return new EngineResult(0, "ok", false);
        } catch (ImportConversionException ex) {
            return new EngineResult(1, ex.getMessage(), false);
        } catch (RuntimeException ex) {
            return new EngineResult(1, String.valueOf(ex.getMessage()), false);
        }
    }
}
