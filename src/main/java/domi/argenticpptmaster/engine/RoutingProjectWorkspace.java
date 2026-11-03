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
 * Project workspace routing: Java is the authority for {@code init}, {@code import-sources},
 * {@code info} and {@code validate}. Optional dual-run diagnostics re-run Python
 * {@code info}/{@code validate} after a successful Java result (warn-only, never overrides
 * the Java outcome) and import diagnostics are written under {@code sources/.dual-run/}
 * (never overwrites authoritative {@code sources/}).
 *
 * <p>Enabling dual-run requires a working Python + ppt-master environment.
 * {@code dual-run-fail-on-mismatch} does not apply to the free-text {@code info}/{@code validate}
 * commands; mismatches are logged as warnings only.
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
        EngineResult javaResult = fileWorkspace.info(project, timeout);
        if (javaResult.successful() && properties.isDualRunEnabled()) {
            dualRunDiagnose("info", project, timeout, javaResult);
        }
        return javaResult;
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        EngineResult javaResult = fileWorkspace.validate(project, timeout);
        if (javaResult.successful() && properties.isDualRunEnabled()) {
            dualRunDiagnose("validate", project, timeout, javaResult);
        }
        return javaResult;
    }

    /**
     * Warn-only Python secondary run for {@code info}/{@code validate}.
     *
     * <p>Exit-code or output mismatches are logged with a line-level diff; Python failures
     * (non-zero exit, timeout, exception) are logged as warnings. Never affects the
     * authoritative Java result.</p>
     */
    private void dualRunDiagnose(String command, Path project, Duration timeout, EngineResult javaResult) {
        try {
            EngineResult pythonResult = "info".equals(command)
                    ? pythonWorkspace.info(project, timeout)
                    : pythonWorkspace.validate(project, timeout);
            if (pythonResult.exitCode() != javaResult.exitCode()
                    || !pythonResult.output().equals(javaResult.output())) {
                log.warn(
                        "workspace {} dual-run mismatch for {}: java exit={} python exit={} timedOut={} diff:\n{}",
                        command,
                        project,
                        javaResult.exitCode(),
                        pythonResult.exitCode(),
                        pythonResult.timedOut(),
                        lineDiff(javaResult.output(), pythonResult.output()));
            } else {
                log.info("workspace {} dual-run match for {} (java is authority)", command, project);
            }
        } catch (RuntimeException ex) {
            log.warn("workspace {} dual-run diagnostic failed; java remains authority", command, ex);
        }
    }

    /** Render differing lines as {@code < java} / {@code > python}, capped for log readability. */
    private static String lineDiff(String javaOutput, String pythonOutput) {
        String[] javaLines = javaOutput.split("\n", -1);
        String[] pythonLines = pythonOutput.split("\n", -1);
        StringBuilder diff = new StringBuilder();
        int max = Math.max(javaLines.length, pythonLines.length);
        int reported = 0;
        for (int i = 0; i < max && reported < 20; i++) {
            String javaLine = i < javaLines.length ? javaLines[i] : "<missing>";
            String pythonLine = i < pythonLines.length ? pythonLines[i] : "<missing>";
            if (!javaLine.equals(pythonLine)) {
                diff.append("< ").append(javaLine).append('\n');
                diff.append("> ").append(pythonLine).append('\n');
                reported++;
            }
        }
        if (diff.isEmpty()) {
            diff.append("<identical text, exit codes differ>");
        }
        return diff.toString();
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
