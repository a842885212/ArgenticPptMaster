package domi.argenticpptmaster.engine.file;

import domi.argenticpptmaster.config.PptImageProperties;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.ImageGenerationGateway;
import domi.argenticpptmaster.engine.file.imagegen.ImageGenerationException;
import domi.argenticpptmaster.engine.file.imagegen.ImageManifestLocker;
import domi.argenticpptmaster.engine.file.imagegen.ImageSizeTable;
import domi.argenticpptmaster.engine.file.imagegen.OpenAiImageClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务内 OpenAI 兼容出图网关：批量处理 {@code images/image_prompts.json}。
 * <p>
 * 与 {@code image_gen.py --manifest} 的行为对齐：只处理 {@code Pending}/{@code Failed}
 * 条目，跳过 {@code Generated}/{@code Needs-Manual}；每条完成即回写清单；本次运行存在失败条目
 * 时以非零退出码结束，成功条目的文件与状态保持已落盘。
 * </p>
 * <p>
 * 与 Python 版的差异：并发度固定为 {@code ppt.image.concurrency}，遇到 429 只对该条目做指数退避
 * 重试，不做全局并发降级；请求体使用 OpenAI 风格（{@code size} 而非 {@code aspect_ratio}）。
 * </p>
 */
public final class JavaImageGenerationGateway implements ImageGenerationGateway {

    /** 清单状态字面量，与 ppt-master {@code image_gen.py} 保持一致。 */
    static final String STATUS_PENDING = "Pending";
    static final String STATUS_GENERATED = "Generated";
    static final String STATUS_FAILED = "Failed";

    private static final Set<String> RETRYABLE_STATUSES = Set.of(STATUS_PENDING, STATUS_FAILED);
    private static final Duration UNBOUNDED_POLL_INTERVAL = Duration.ofSeconds(30);

    private final PptImageProperties properties;
    private final OpenAiImageClient client;

    /**
     * @param properties 出图配置；base-url 缺失时本网关在任何 HTTP 调用前返回失败结果
     * @param client     OpenAI 兼容客户端，密钥回落逻辑由装配方在构造客户端时完成
     */
    public JavaImageGenerationGateway(PptImageProperties properties, OpenAiImageClient client) {
        if (properties == null || client == null) {
            throw new IllegalArgumentException("properties and client are required");
        }
        this.properties = properties;
        this.client = client;
    }

    @Override
    public EngineResult generateFromManifest(Path manifest, Path outputDir, Duration timeout) {
        if (!properties.hasBaseUrl()) {
            return new EngineResult(1, PptImageProperties.MISSING_BASE_URL_MESSAGE, false);
        }
        List<Map<String, Object>> items;
        try {
            items = itemsOf(ImageManifestLocker.read(manifest));
        } catch (IOException | RuntimeException ex) {
            return new EngineResult(1, "image manifest is invalid: " + describe(ex), false);
        }
        List<Map<String, Object>> targets = items.stream()
                .filter(item -> RETRYABLE_STATUSES.contains(text(item.get("status"))))
                .toList();
        int preSkipped = items.size() - targets.size();
        if (targets.isEmpty()) {
            return new EngineResult(0,
                    "[Manifest] Nothing to do - all %d item(s) already Generated or Needs-Manual"
                            .formatted(items.size()), false);
        }
        String duplicate = firstDuplicateFilename(items);
        if (duplicate != null) {
            return new EngineResult(1, "image manifest has duplicate filename: " + duplicate
                    + "; per-item status write-back requires unique filenames", false);
        }
        try {
            Files.createDirectories(outputDir);
        } catch (IOException ex) {
            return new EngineResult(1, "cannot create image output directory " + outputDir + ": "
                    + ex.getMessage(), false);
        }

        int concurrency = Math.min(properties.effectiveConcurrency(), targets.size());
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        lines.add("[Manifest] %d item(s) to generate, %d already done. concurrency=%d"
                .formatted(targets.size(), preSkipped, concurrency));

        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger failedCount = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, newImageThreadFactory());
        boolean timedOut = false;
        try {
            for (Map<String, Object> item : targets) {
                pool.submit(() -> processItem(manifest, outputDir, item, lines, okCount, failedCount));
            }
            pool.shutdown();
            timedOut = !awaitTermination(pool, timeout);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            timedOut = true;
            lines.add("[Manifest] interrupted while waiting for generation threads");
        } finally {
            if (timedOut) {
                pool.shutdownNow();
            }
        }
        if (timedOut) {
            lines.add("[Manifest] command timeout after %s; interrupted item(s) are recorded as %s"
                    .formatted(timeout, STATUS_FAILED));
        }

        int failed = failedCount.get();
        lines.add("");
        lines.add("[Manifest] Done: %d ok / %d failed (%d pre-skipped). Manifest written to %s"
                .formatted(okCount.get(), failed, preSkipped, manifest));
        boolean unsuccessful = failed > 0 || timedOut;
        if (unsuccessful) {
            lines.add("[Manifest] Failed is retryable: rerun generate_project_images"
                    + " or mark the item Needs-Manual before the executor step.");
        }
        // 超时后可能仍有 worker 在追加日志，先取快照再拼接，避免并发遍历。
        List<String> snapshot;
        synchronized (lines) {
            snapshot = new ArrayList<>(lines);
        }
        return new EngineResult(unsuccessful ? 1 : 0, String.join("\n", snapshot), timedOut);
    }

    private void processItem(
            Path manifest,
            Path outputDir,
            Map<String, Object> item,
            List<String> lines,
            AtomicInteger okCount,
            AtomicInteger failedCount) {
        String filename = text(item.get("filename"));
        String lastError = null;
        try {
            generateAndWrite(outputDir, item, filename);
        } catch (IOException | RuntimeException ex) {
            lastError = describe(ex);
        }
        boolean generated = lastError == null;
        if (filename == null) {
            failedCount.incrementAndGet();
            lines.add("  [FAIL] <item without filename>: " + lastError);
            return;
        }
        boolean written;
        try {
            written = ImageManifestLocker.updateItemStatus(
                    manifest, filename, generated ? STATUS_GENERATED : STATUS_FAILED,
                    generated ? null : lastError);
        } catch (IOException | RuntimeException ex) {
            // 状态没落到清单上，本批结果不可信：即使图片已写出也不能记成功。
            failedCount.incrementAndGet();
            lines.add("  [FAIL] " + filename + ": manifest write-back failed: " + ex.getMessage());
            return;
        }
        if (!written) {
            failedCount.incrementAndGet();
            lines.add("  [FAIL] " + filename
                    + ": item is no longer present in the manifest, status could not be written back");
            return;
        }
        if (generated) {
            okCount.incrementAndGet();
            lines.add("  [OK]   " + filename);
        } else {
            failedCount.incrementAndGet();
            lines.add("  [FAIL] " + filename + ": " + lastError);
        }
    }

    private void generateAndWrite(Path outputDir, Map<String, Object> item, String filename) throws IOException {
        if (filename == null) {
            throw new ImageGenerationException("manifest item requires a non-blank filename");
        }
        String prompt = text(item.get("prompt"));
        if (prompt == null) {
            throw new ImageGenerationException("manifest item requires a non-blank prompt");
        }
        String aspectRatio = text(item.get("aspect_ratio"));
        String size = ImageSizeTable.resolve(aspectRatio).orElseThrow(() -> new ImageGenerationException(
                "unsupported aspect_ratio: " + (aspectRatio == null ? "<missing>" : aspectRatio)
                        + "; supported ratios " + ImageSizeTable.supported()));
        Path target = resolveTarget(outputDir, filename);
        byte[] image = client.generate(prompt, size);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.write(target, image);
    }

    /**
     * 将清单里的 {@code filename} 解析到输出目录内。
     * 清单由模型侧写入，因此必须阻止绝对路径与 {@code ..} 越界写文件。
     */
    private static Path resolveTarget(Path outputDir, String filename) {
        Path base = outputDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(filename).normalize();
        if (resolved.equals(base) || !resolved.startsWith(base)) {
            throw new ImageGenerationException("filename must stay inside the images directory: " + filename);
        }
        return resolved;
    }

    private static boolean awaitTermination(ExecutorService pool, Duration timeout) throws InterruptedException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            // 命令级超时未配置时不设人为上限：出图是长任务，但单条请求仍受 item-timeout 约束。
            while (!pool.awaitTermination(UNBOUNDED_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS)) {
                // 继续等待
            }
            return true;
        }
        return pool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static ThreadFactory newImageThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "java-image-gen-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> manifest) {
        if (!(manifest.get("items") instanceof List<?> raw)) {
            throw new ImageGenerationException("manifest items are missing or not a list");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object candidate : raw) {
            if (!(candidate instanceof Map<?, ?> map)) {
                throw new ImageGenerationException("manifest item is not a JSON object");
            }
            items.add((Map<String, Object>) map);
        }
        return items;
    }

    /**
     * 回写按 {@code filename} 定位条目，因此重复文件名会让状态落到错误的条目上。
     * 清单由模型侧写入，这里在发第一批请求前先拒绝这种歧义状态。
     */
    private static String firstDuplicateFilename(List<Map<String, Object>> items) {
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> item : items) {
            String filename = text(item.get("filename"));
            if (filename != null && !seen.add(filename)) {
                return filename;
            }
        }
        return null;
    }

    private static String describe(Exception ex) {
        String message = ex.getMessage();
        if (ex instanceof ImageGenerationException || ex instanceof IllegalStateException) {
            return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
        }
        return ex.getClass().getSimpleName() + ": " + (message == null ? "" : message);
    }

    private static String text(Object value) {
        if (!(value instanceof String string) || string.isBlank()) {
            return null;
        }
        return string.trim();
    }
}
