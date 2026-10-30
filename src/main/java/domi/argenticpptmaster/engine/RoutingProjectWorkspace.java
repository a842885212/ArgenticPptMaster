package domi.argenticpptmaster.engine;

import domi.argenticpptmaster.config.PptEngineProperties;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-source project import routing: Java handles file + Office/PDF MD converters; Python retains
 * URL and pandoc-long-tail document conversion.
 */
public final class RoutingProjectWorkspace implements ProjectWorkspace {

    private static final Logger log = LoggerFactory.getLogger(RoutingProjectWorkspace.class);

    /** Formats that still require Python converters (not covered by Java import authority). */
    private static final Set<String> PYTHON_IMPORT_SUFFIXES = Set.of(
            ".doc",
            ".odt",
            ".rtf",
            ".epub",
            ".ipynb",
            ".tex",
            ".latex",
            ".rst",
            ".org",
            ".typ");

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
        List<Path> javaSources = new ArrayList<>();
        List<Path> pythonSources = new ArrayList<>();
        for (Path source : expanded) {
            if (requiresPythonImport(source)) {
                pythonSources.add(source);
            } else {
                javaSources.add(source);
            }
        }

        if (javaSources.isEmpty() && pythonSources.isEmpty()) {
            return fileWorkspace.importSources(project, sources == null ? List.of() : sources, copy, timeout);
        }

        EngineResult javaResult = null;
        if (!javaSources.isEmpty()) {
            javaResult = fileWorkspace.importSources(project, javaSources, copy, timeout);
            if (!javaResult.successful()) {
                return javaResult;
            }
            maybeDualRun(project, javaSources, copy, timeout);
        }

        if (pythonSources.isEmpty()) {
            return javaResult != null
                    ? javaResult
                    : new EngineResult(1, "[ERROR] No sources to import", false);
        }

        EngineResult pythonResult = pythonWorkspace.importSources(project, pythonSources, copy, timeout);
        if (javaResult == null) {
            return pythonResult;
        }
        return mergeResults(javaResult, pythonResult);
    }

    @Override
    public EngineResult info(Path project, Duration timeout) {
        return pythonWorkspace.info(project, timeout);
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        return pythonWorkspace.validate(project, timeout);
    }

    private void maybeDualRun(Path project, List<Path> javaSources, boolean copy, Duration timeout) {
        if (!properties.isImportPythonDualRun()) {
            return;
        }
        try {
            EngineResult dual = pythonWorkspace.importSources(project, javaSources, copy, timeout);
            log.info(
                    "import dual-run diagnostic exit={} timedOut={} sources={}",
                    dual.exitCode(),
                    dual.timedOut(),
                    javaSources.size());
        } catch (RuntimeException ex) {
            log.warn("import dual-run diagnostic failed: {}", ex.toString());
        }
    }

    private static EngineResult mergeResults(EngineResult javaResult, EngineResult pythonResult) {
        StringBuilder out = new StringBuilder();
        if (javaResult.output() != null && !javaResult.output().isBlank()) {
            out.append(javaResult.output().stripTrailing());
        }
        if (pythonResult.output() != null && !pythonResult.output().isBlank()) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append(pythonResult.output().stripTrailing());
        }
        // Java already succeeded; Python import-sources is soft for remaining formats.
        return new EngineResult(0, out.toString(), javaResult.timedOut() || pythonResult.timedOut());
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
            if (isUrl(source.toString())) {
                expanded.add(source);
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

    private static boolean requiresPythonImport(Path source) {
        if (source == null) {
            return false;
        }
        String raw = source.toString();
        if (isUrl(raw)) {
            return true;
        }
        return needsPythonConversion(source);
    }

    private static boolean needsPythonConversion(Path path) {
        if (path == null || isUrl(path.toString()) || Files.isDirectory(path)) {
            return false;
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return PYTHON_IMPORT_SUFFIXES.contains(name.substring(dot));
    }

    private static boolean isUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
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
