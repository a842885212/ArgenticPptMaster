package domi.argenticpptmaster.engine;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Sends file-only project commands to the Java workspace and leaves Office, PDF, and URL
 * conversion on the Python workspace.
 */
public final class RoutingProjectWorkspace implements ProjectWorkspace {

    private static final Set<String> PYTHON_IMPORT_SUFFIXES = Set.of(
            ".pdf",
            ".pptx", ".pptm", ".ppsx", ".ppsm", ".potx", ".potm", ".ppt",
            ".xlsx", ".xlsm",
            ".docx", ".doc", ".odt", ".rtf", ".epub", ".html", ".htm",
            ".tex", ".latex", ".rst", ".org", ".ipynb", ".typ");

    private final ProjectWorkspace fileWorkspace;
    private final ProjectWorkspace pythonWorkspace;

    public RoutingProjectWorkspace(ProjectWorkspace fileWorkspace, ProjectWorkspace pythonWorkspace) {
        this.fileWorkspace = fileWorkspace;
        this.pythonWorkspace = pythonWorkspace;
    }

    @Override
    public EngineResult init(String projectName, String format, Path projectsDir, Duration timeout) {
        return fileWorkspace.init(projectName, format, projectsDir, timeout);
    }

    @Override
    public EngineResult importSources(Path project, List<Path> sources, boolean copy, Duration timeout) {
        if (requiresPythonImport(sources)) {
            return pythonWorkspace.importSources(project, sources, copy, timeout);
        }
        return fileWorkspace.importSources(project, sources, copy, timeout);
    }

    @Override
    public EngineResult info(Path project, Duration timeout) {
        return pythonWorkspace.info(project, timeout);
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        return pythonWorkspace.validate(project, timeout);
    }

    private static boolean requiresPythonImport(List<Path> sources) {
        if (sources == null || sources.isEmpty()) {
            return false;
        }
        for (Path source : sources) {
            if (source == null) {
                continue;
            }
            String raw = source.toString();
            if (isUrl(raw)) {
                return true;
            }
            if (needsPythonConversion(source)) {
                return true;
            }
            if (Files.isDirectory(source)) {
                try (Stream<Path> children = Files.list(source)) {
                    if (children.anyMatch(RoutingProjectWorkspace::needsPythonConversion)) {
                        return true;
                    }
                } catch (IOException ex) {
                    return true;
                }
            }
        }
        return false;
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
