package domi.argenticpptmaster.engine;

import java.nio.file.Path;
import java.time.Duration;

/** Converts a PPTX export to markdown for read-back validation. */
public interface PptxMarkdownReadback {

    EngineResult toMarkdown(Path pptx, Path markdownOut, Duration timeout);
}
