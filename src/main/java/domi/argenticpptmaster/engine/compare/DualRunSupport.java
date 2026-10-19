package domi.argenticpptmaster.engine.compare;

import domi.argenticpptmaster.config.PptEngineProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skeleton for dual-running primary vs secondary exporters and comparing slide XML.
 *
 * <p>Disabled by default via {@link PptEngineProperties#isDualRunEnabled()}. When enabled and a
 * secondary exporter is supplied, both outputs must compare equal or a failure is logged /
 * thrown depending on {@link PptEngineProperties#isDualRunFailOnMismatch()}.</p>
 */
public final class DualRunSupport {

    private static final Logger log = LoggerFactory.getLogger(DualRunSupport.class);

    private final PptEngineProperties properties;
    private final SlideXmlComparator comparator;

    public DualRunSupport(PptEngineProperties properties, SlideXmlComparator comparator) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.comparator = Objects.requireNonNullElseGet(comparator, SlideXmlComparator::new);
    }

    public DualRunSupport(PptEngineProperties properties) {
        this(properties, new SlideXmlComparator());
    }

    /**
     * Runs {@code primary}, optionally dual-runs {@code secondary} into a temp file, and compares.
     *
     * @param workDir     staging directory for the secondary output
     * @param primaryOut  PPTX produced by the primary implementation
     * @param primary     already-executed primary (returns {@code primaryOut})
     * @param secondary   optional secondary exporter that writes a PPTX and returns its path
     * @return the primary PPTX path
     */
    public Path compareExports(
            Path workDir,
            Path primaryOut,
            Function<Path, Path> primary,
            Function<Path, Path> secondary) throws IOException {
        Path primaryPath = primary.apply(primaryOut);
        if (!properties.isDualRunEnabled() || secondary == null) {
            return primaryPath;
        }
        Path secondaryDir = Files.createDirectories(workDir.resolve("dual-run-secondary"));
        Path secondaryOut = secondaryDir.resolve(primaryPath.getFileName().toString());
        Path secondaryPath = secondary.apply(secondaryOut);
        SlideXmlComparator.Result result = comparator.compare(primaryPath, secondaryPath);
        if (result.equal()) {
            log.info("engine dual-run slide XML match for {}", primaryPath.getFileName());
            return primaryPath;
        }
        String summary = "engine dual-run slide XML mismatch for " + primaryPath.getFileName()
                + ": diffs=" + result.diffs().size();
        if (properties.isDualRunFailOnMismatch()) {
            throw new IllegalStateException(summary);
        }
        log.warn(summary);
        return primaryPath;
    }
}
