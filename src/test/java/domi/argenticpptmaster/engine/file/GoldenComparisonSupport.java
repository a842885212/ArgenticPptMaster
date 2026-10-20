package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class GoldenComparisonSupport {

    static final String DEFAULT_PIN_ROOT = "/tmp/ppt-master-cc535363";
    static final String DEFAULT_PYTHON = "/media/zhang/E/venvs/ppt-master/bin/python";
    static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(60);
    static final Pattern PROJECT_CREATED =
            Pattern.compile("^Project created: (.+)$", Pattern.MULTILINE);
    static final Pattern WORKFLOW_HEADER =
            Pattern.compile("(?m)^=== .+? NOTE id=\\w+ ===$");
    static final Pattern DATE_SUFFIX = Pattern.compile("_(\\d{8})$");

    private GoldenComparisonSupport() {}

    static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    static Path pinRoot() {
        return Path.of(envOrDefault("PPT_MASTER_PIN_ROOT", DEFAULT_PIN_ROOT));
    }

    static Path pythonExecutable() {
        return Path.of(envOrDefault("PPT_MASTER_PYTHON", DEFAULT_PYTHON));
    }

    static Path projectManagerScript(Path pinRoot) {
        return pinRoot.resolve("skills/ppt-master/scripts/project_manager.py");
    }

    static Path totalMdSplitScript(Path pinRoot) {
        return pinRoot.resolve("skills/ppt-master/scripts/total_md_split.py");
    }

    static Path svgQualityCheckerScript(Path pinRoot) {
        return pinRoot.resolve("skills/ppt-master/scripts/svg_quality_checker.py");
    }

    static boolean goldenEnvironmentReady(Path pinRoot, Path python, Path projectManager, Path totalMdSplit) {
        return goldenEnvironmentReady(
                pinRoot, python, projectManager, totalMdSplit, svgQualityCheckerScript(pinRoot));
    }

    static boolean goldenEnvironmentReady(Path pinRoot, Path python, Path... requiredScripts) {
        if (!Files.isDirectory(pinRoot) || !Files.isExecutable(python)) {
            return false;
        }
        for (Path script : requiredScripts) {
            if (!Files.isRegularFile(script)) {
                return false;
            }
        }
        return true;
    }

    static ProcessResult runPython(Path python, Path script, List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(python.toString());
        command.add(script.toString());
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ProcessResult(1, "", true);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output, false);
    }

    static Path parseProjectCreatedPath(String output) {
        Matcher matcher = PROJECT_CREATED.matcher(output);
        if (!matcher.find()) {
            throw new IllegalStateException("Project created line not found in output:\n" + output);
        }
        return Path.of(matcher.group(1).trim());
    }

    static String extractDateFromProjectDir(Path projectPath) {
        Matcher matcher = DATE_SUFFIX.matcher(projectPath.getFileName().toString());
        if (!matcher.find()) {
            throw new IllegalStateException("No YYYYMMDD suffix in " + projectPath.getFileName());
        }
        return matcher.group(1);
    }

    static Clock clockAtLocalNoon(String yyyymmdd) {
        LocalDate date = LocalDate.parse(yyyymmdd, DateTimeFormatter.BASIC_ISO_DATE);
        ZoneId zone = ZoneId.systemDefault();
        return Clock.fixed(date.atTime(LocalTime.NOON).atZone(zone).toInstant(), zone);
    }

    static List<String> listRelativePaths(Path projectRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            return walk.map(projectRoot::relativize)
                    .filter(relative -> !relative.toString().isEmpty())
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    static Map<String, byte[]> relativeFileBytes(Path projectRoot, String subdirectory) throws IOException {
        Path base = projectRoot.resolve(subdirectory);
        if (!Files.isDirectory(base)) {
            return Map.of();
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) {
                String relative = subdirectory + "/" + base.relativize(path).toString().replace('\\', '/');
                files.put(relative, Files.readAllBytes(path));
            }
        }
        return files;
    }

    static String normalizeProjectPaths(String text, Path project) {
        if (text == null) {
            return "";
        }
        Path absolute = project.toAbsolutePath().normalize();
        String normalized = text.replace(absolute.toString(), "{PROJECT}");
        String withTrailingSlash = absolute.toString() + "/";
        return normalized.replace(withTrailingSlash, "{PROJECT}/");
    }

    static String normalizeWorkflowLog(String log, Path project) {
        String normalized = normalizeProjectPaths(log, project);
        return WORKFLOW_HEADER.matcher(normalized).replaceAll("=== {TIMESTAMP} NOTE id={NOTE_ID} ===");
    }

    static String normalizeCommandOutput(String output, Path project) {
        return normalizeProjectPaths(output, project).stripTrailing();
    }

    static void assertSameRelativeFileBytes(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        TreeMap<String, byte[]> sortedExpected = new TreeMap<>(expected);
        for (Map.Entry<String, byte[]> entry : sortedExpected.entrySet()) {
            assertThat(actual.get(entry.getKey()))
                    .as("content of %s", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    record ProcessResult(int exitCode, String output, boolean timedOut) {}
}
