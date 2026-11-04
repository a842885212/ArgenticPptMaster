package domi.argenticpptmaster.engine.file.importconvert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 双跑 parity：Python {@code source_to_md/ppt_to_md.py}（ground truth）vs Java {@link PptxToMarkdownConverter}。
 *
 * <p>环境经 {@code PPT_MASTER_PIN_ROOT}（ppt-master 仓库路径）与 {@code PPT_MASTER_READBACK_PYTHON}
 * （需已安装 python-pptx&lt;0.7）提供；缺失时按 assumption 跳过。</p>
 *
 * <p>{@link #KNOWN_DIVERGENCES} 是迁移期白名单：特性移植完成前，fixture 输出差异必须被白名单条目解释；
 * 移植完成后该表必须为空（即所有 fixture 零分歧）。每条必须注释原因并关联特性。</p>
 */
class PptxToMarkdownParityTests {

    private static final Path FIXTURE_DIR = Path.of("src/test/resources/readback");
    private static final String SCRIPT = "skills/ppt-master/scripts/source_to_md/ppt_to_md.py";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 特性移植完成前已知的预期差异。移植任务完成后必须逐项移除直至两表均为空。
     *
     * <p>{@link #KNOWN_DIVERGENT_FIXTURES}：整 deck 已知分歧（差异行多且同源），按 fixture 豁免并注明原因；
     * {@link #KNOWN_DIVERGENCES}：行级差异，每条 diff 行必须命中其中某个子串。</p>
     */
    // 特性移植已完成：白名单必须保持为空（任何分歧都会使本测试失败）。
    private static final java.util.Map<String, String> KNOWN_DIVERGENT_FIXTURES = java.util.Map.of();

    private static final java.util.Map<String, List<String>> KNOWN_DIVERGENCES = java.util.Map.of();

    private Path pinRoot;
    private Path python;

    @BeforeEach
    void assumeDualRunEnvironment() throws Exception {
        String envRoot = System.getenv("PPT_MASTER_PIN_ROOT");
        pinRoot = Path.of(envRoot == null || envRoot.isBlank()
                ? "/home/zhang/PycharmProjects/ppt-master"
                : envRoot.trim());
        String envPython = System.getenv("PPT_MASTER_READBACK_PYTHON");
        python = Path.of(envPython == null || envPython.isBlank()
                ? System.getProperty("user.home") + "/.venv/pptx-parity/bin/python"
                : envPython.trim());
        assumeTrue(Files.isRegularFile(pinRoot.resolve(SCRIPT)), "ppt-master 脚本不存在: " + pinRoot);
        assumeTrue(Files.isRegularFile(python) || isOnPath(python), "python 不存在: " + python);
        assumeTrue(pythonPptxAvailable(), "python-pptx 不可用: " + python);
    }

    @Test
    void fixtureCorpusMatchesPythonGroundTruth(@TempDir Path tempDir) throws Exception {
        List<Path> fixtures;
        try (Stream<Path> stream = Files.list(FIXTURE_DIR)) {
            fixtures = stream.filter(path -> path.getFileName().toString().endsWith(".pptx")).sorted().toList();
        }
        assertThat(fixtures).as("readback fixture corpus").isNotEmpty();

        List<String> failures = new ArrayList<>();
        for (Path fixture : fixtures) {
            String stem = stem(fixture);
            Path pythonOut = tempDir.resolve("python").resolve(stem + ".md");
            Path javaOut = tempDir.resolve("java").resolve(stem + ".md");
            Files.createDirectories(pythonOut.getParent());
            Files.createDirectories(javaOut.getParent());

            ProcessResult pythonRun = runPython(fixture, pythonOut);
            assumeTrue(pythonRun.exitCode() == 0,
                    "Python ppt_to_md 运行失败: " + pythonRun.output());

            PptxToMarkdownConverter.convert(fixture, javaOut);

            List<String> diff = new ArrayList<>(markdownDiff(pythonOut, javaOut));
            diff.addAll(manifestDiff(pythonOut, javaOut));
            diff.addAll(assetDiff(pythonOut, javaOut));

            String fixtureName = fixture.getFileName().toString();
            if (KNOWN_DIVERGENT_FIXTURES.containsKey(fixtureName)) {
                continue;
            }
            List<String> whitelisted = KNOWN_DIVERGENCES.getOrDefault(fixtureName, List.of());
            List<String> hunk = new ArrayList<>();
            for (String line : diff) {
                if (!line.startsWith("  ") && !hunk.isEmpty()) {
                    collectUncovered(failures, fixtureName, hunk, whitelisted);
                    hunk.clear();
                }
                hunk.add(line);
            }
            if (!hunk.isEmpty()) {
                collectUncovered(failures, fixtureName, hunk, whitelisted);
            }
        }
        assertThat(failures).as("白名单外的双跑分歧").isEmpty();
    }

    /** diff 以「标题行 + 若干 {@code "  Lxx"} 明细行」为 hunk；hunk 内任一行命中白名单即视为已解释。 */
    private static void collectUncovered(
            List<String> failures, String fixtureName, List<String> hunk, List<String> whitelisted) {
        boolean covered = hunk.stream()
                .anyMatch(line -> whitelisted.stream().anyMatch(line::contains));
        if (!covered) {
            hunk.forEach(line -> failures.add(fixtureName + ": " + line));
        }
    }

    private ProcessResult runPython(Path fixture, Path output) throws Exception {
        List<String> command = List.of(
                python.toString(),
                pinRoot.resolve(SCRIPT).toString(),
                fixture.toAbsolutePath().toString(),
                "-o", output.toAbsolutePath().toString());
        ProcessBuilder builder = new ProcessBuilder(command).directory(pinRoot.toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        process.getInputStream().transferTo(buffer);
        boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        return new ProcessResult(finished ? process.exitValue() : -1,
                buffer.toString(StandardCharsets.UTF_8));
    }

    private List<String> markdownDiff(Path pythonOut, Path javaOut) throws Exception {
        if (!Files.isRegularFile(javaOut)) {
            return List.of("java markdown 未产出: " + javaOut.getFileName());
        }
        String pythonMd = normalize(Files.readString(pythonOut, StandardCharsets.UTF_8));
        String javaMd = normalize(Files.readString(javaOut, StandardCharsets.UTF_8));
        if (pythonMd.equals(javaMd)) {
            return List.of();
        }
        List<String> diff = new ArrayList<>();
        diff.add("markdown 分歧 " + pythonOut.getFileName() + ":");
        String[] pythonLines = pythonMd.split("\n");
        String[] javaLines = javaMd.split("\n");
        int max = Math.max(pythonLines.length, javaLines.length);
        for (int i = 0; i < max; i++) {
            String py = i < pythonLines.length ? pythonLines[i] : "<missing>";
            String ja = i < javaLines.length ? javaLines[i] : "<missing>";
            if (!py.equals(ja)) {
                diff.add("  L" + (i + 1) + " python: " + py);
                diff.add("  L" + (i + 1) + " java:   " + ja);
                if (diff.size() > 40) {
                    diff.add("  ...（截断）");
                    break;
                }
            }
        }
        return diff;
    }

    private List<String> manifestDiff(Path pythonOut, Path javaOut) throws Exception {
        Path pythonManifest = manifestPath(pythonOut);
        Path javaManifest = manifestPath(javaOut);
        boolean pythonHas = Files.isRegularFile(pythonManifest);
        boolean javaHas = Files.isRegularFile(javaManifest);
        if (pythonHas && javaHas) {
            JsonNode pythonTree = OBJECT_MAPPER.readTree(Files.readString(pythonManifest, StandardCharsets.UTF_8));
            JsonNode javaTree = OBJECT_MAPPER.readTree(Files.readString(javaManifest, StandardCharsets.UTF_8));
            if (pythonTree.equals(javaTree)) {
                return List.of();
            }
            List<String> detail = new ArrayList<>();
            detail.add("image_manifest.json 内容分歧 " + pythonOut.getFileName());
            detail.addAll(manifestFieldDiff(pythonTree, javaTree, "$"));
            return detail;
        }
        if (pythonHas != javaHas) {
            return List.of("image_manifest.json 仅一侧存在 " + pythonOut.getFileName());
        }
        return List.of();
    }

    private static List<String> manifestFieldDiff(JsonNode python, JsonNode java, String path) {
        List<String> diff = new ArrayList<>();
        if (python.isObject() && java.isObject()) {
            java.util.Iterator<String> names = python.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                String childPath = path + "." + name;
                JsonNode py = python.get(name);
                JsonNode ja = java.get(name);
                if (ja == null) {
                    diff.add("  " + childPath + " 缺失(java)");
                } else if (!py.equals(ja)) {
                    diff.addAll(manifestFieldDiff(py, ja, childPath));
                }
            }
            java.util.Iterator<String> javaNames = java.fieldNames();
            while (javaNames.hasNext()) {
                String name = javaNames.next();
                if (!python.has(name)) {
                    diff.add("  " + path + "." + name + " 多余(java)=" + java.get(name));
                }
            }
            return diff;
        }
        if (python.isArray() && java.isArray()) {
            int size = Math.max(python.size(), java.size());
            for (int i = 0; i < size; i++) {
                JsonNode py = i < python.size() ? python.get(i) : null;
                JsonNode ja = i < java.size() ? java.get(i) : null;
                if (py == null || ja == null || !py.equals(ja)) {
                    diff.addAll(manifestFieldDiff(py, ja, path + "[" + i + "]"));
                }
            }
            return diff;
        }
        diff.add("  " + path + " python=" + python + " java=" + java);
        return diff;
    }

    private List<String> assetDiff(Path pythonOut, Path javaOut) throws Exception {
        Set<String> pythonAssets = assetNames(pythonOut);
        Set<String> javaAssets = assetNames(javaOut);
        if (pythonAssets.equals(javaAssets)) {
            return List.of();
        }
        return List.of("_files 资源集合分歧 " + pythonOut.getFileName()
                + " python=" + pythonAssets + " java=" + javaAssets);
    }

    private Set<String> assetNames(Path markdownOut) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        Path assetDir = assetDir(markdownOut);
        if (Files.isDirectory(assetDir)) {
            try (Stream<Path> stream = Files.list(assetDir)) {
                for (Path path : stream.filter(Files::isRegularFile).toList()) {
                    if (!path.getFileName().toString().equals("image_manifest.json")) {
                        names.add(path.getFileName().toString());
                    }
                }
            }
        }
        return names;
    }

    private Path manifestPath(Path markdownOut) {
        return assetDir(markdownOut).resolve("image_manifest.json");
    }

    private Path assetDir(Path markdownOut) {
        return markdownOut.resolveSibling(stem(markdownOut) + "_files");
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 与 Python {@code normalize_text} 对齐的空白规范化（逐行折叠 + 去空行）。 */
    private static String normalize(String value) {
        StringBuilder result = new StringBuilder();
        for (String raw : value.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            String collapsed = raw.replaceAll("\\s+", " ").strip();
            if (!collapsed.isEmpty()) {
                result.append(collapsed).append('\n');
            }
        }
        return result.toString();
    }

    private boolean pythonPptxAvailable() throws Exception {
        Process process = new ProcessBuilder(
                python.toString(), "-c", "import pptx, PIL; print(pptx.__version__)")
                .redirectErrorStream(true).start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        return finished && process.exitValue() == 0;
    }

    private static boolean isOnPath(Path candidate) {
        return candidate.getNameCount() == 1;
    }

    private record ProcessResult(int exitCode, String output) {}
}
