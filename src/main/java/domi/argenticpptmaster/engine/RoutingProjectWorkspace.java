package domi.argenticpptmaster.engine;

import domi.argenticpptmaster.config.PptEngineProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Project import routing: Java is the sole authority for {@code import-sources}.
 * Python remains for {@code info}/{@code validate} and optional dual-run diagnostics
 * written under {@code sources/.dual-run/} (never overwrites authoritative {@code sources/}).
 *
 * <p>Enabling import dual-run requires a working Python + ppt-master environment.
 */
public final class RoutingProjectWorkspace implements ProjectWorkspace {

    private static final Logger log = LoggerFactory.getLogger(RoutingProjectWorkspace.class);

    /** Sidecar project root relative to the real project: {@code sources/.dual-run/}. */
    public static final String DUAL_RUN_SIDECAR = "sources/.dual-run";

    private final ProjectWorkspace fileWorkspace;
    private final ProjectWorkspace pythonWorkspace;
    private final PptEngineProperties properties;

    public RoutingProjectWorkspace(ProjectWorkspace fileWorkspace, ProjectWorkspace pythonWorkspace) {
        this(fileWorkspace, pythonWorkspace, PptEngineProperties.defaults());
    }

    public RoutingProjectWorkspace(
            ProjectWorkspace fileWorkspace,
            ProjectWorkspace pythonWorkspace,
            PptEngineProperties properties) {
        this.fileWorkspace = fileWorkspace;
        this.pythonWorkspace = pythonWorkspace;
        this.properties = properties == null ? PptEngineProperties.defaults() : properties;
    }

    @Override
    public EngineResult init(String projectName, String format, Path projectsDir, Duration timeout) {
        return fileWorkspace.init(projectName, format, projectsDir, timeout);
    }

    @Override
    public EngineResult importSources(Path project, List<Path> sources, boolean copy, Duration timeout) {
        List<Path> expanded = expandSources(sources);
        if (expanded.isEmpty()) {
            return fileWorkspace.importSources(project, sources == null ? List.of() : sources, copy, timeout);
        }

        EngineResult javaResult = fileWorkspace.importSources(project, expanded, copy, timeout);
        if (javaResult.successful()) {
            maybeDualRun(project, expanded, copy, timeout);
        }
        return javaResult;
    }

    @Override
    public EngineResult info(Path project, Duration timeout) {
        return pythonWorkspace.info(project, timeout);
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        return pythonWorkspace.validate(project, timeout);
    }

    /**
     * Optional Python diagnostic import into {@link #DUAL_RUN_SIDECAR}. Never overwrites
     * authoritative {@code sources/} Markdown/media and never flips success/failure.
     */
    private void maybeDualRun(Path project, List<Path> javaSources, boolean copy, Duration timeout) {
        if (!properties.isImportPythonDualRun()) {
            return;
        }
        try {
            Path dualProject = project.resolve(DUAL_RUN_SIDECAR);
            Files.createDirectories(dualProject.resolve("sources"));
            EngineResult dual = pythonWorkspace.importSources(dualProject, javaSources, copy, timeout);
            log.info(
                    "import dual-run diagnostic exit={} timedOut={} sidecar={} sources={} "
                            + "(requires Python + ppt-master; does not overwrite authoritative sources/)",
                    dual.exitCode(),
                    dual.timedOut(),
                    dualProject,
                    javaSources.size());
        } catch (RuntimeException | IOException ex) {
            log.warn("import dual-run diagnostic failed: {}", ex.toString());
        }
    }

    private static List<Path> expandSources(List<Path> sources) {
        List<Path> expanded = new ArrayList<>();
        if (sources == null) {
            return expanded;
        }
        for (Path source : sources) {
            if (source == null) {
                continue;
            }
            String raw = restoreUrlFromPathString(source.toString());
            if (isUrl(raw)) {
                // Re-wrap so downstream toString() keeps a parseable http(s) URL.
                expanded.add(Path.of(raw));
                continue;
            }
            if (Files.isDirectory(source)) {
                try (Stream<Path> children = Files.list(source)) {
                    children.filter(Files::isRegularFile).sorted().forEach(expanded::add);
                } catch (IOException ex) {
                    expanded.add(source);
                }
                continue;
            }
            expanded.add(source);
        }
        return expanded;
    }

    /**
     * {@link Path#of(String, String...)} collapses {@code https://} to {@code https:/} on Unix.
     */
    static String restoreUrlFromPathString(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.matches("(?i)https?:/[^/].*")) {
            return trimmed.replaceFirst("(?i)^(https?):/", "$1://");
        }
        return trimmed;
    }

    private static boolean isUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(restoreUrlFromPathString(value));
            String scheme = uri.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
