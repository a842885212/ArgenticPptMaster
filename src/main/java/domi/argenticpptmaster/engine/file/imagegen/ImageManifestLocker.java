package domi.argenticpptmaster.engine.file.imagegen;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@code images/image_prompts.json} 的进程内互斥回写工具。
 * <p>
 * 出图按条目并发完成，每完成一条都要读-改-写同一份清单。这里用「按规范化绝对路径分组的
 * {@link ReentrantLock} + 临时文件 + {@code ATOMIC_MOVE}」保证：并发回写不会交错，
 * 读者永远看不到半截 JSON。锁只在单个 JVM 内有效，跨实例并发写同一项目不在 v1 范围内。
 * </p>
 */
public final class ImageManifestLocker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ERROR_CHARS = 500;
    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private ImageManifestLocker() {
    }

    /**
     * 读取清单原始内容。
     *
     * @param manifestFile 清单路径
     * @return 顶层键值结构（{@code items} 为 {@code List<Map<String, Object>>}）
     * @throws IOException 读取或解析失败
     */
    public static Map<String, Object> read(Path manifestFile) throws IOException {
        return MAPPER.readValue(manifestFile.toFile(), new TypeReference<>() {});
    }

    /**
     * 在互斥保护下更新单个条目的状态字段。
     * <p>
     * 以 {@code filename} 定位条目，因此并发任务之间不会互相覆盖索引；清单中其余条目和顶层字段
     * （如 {@code deck_style_anchor}、{@code color_scheme}）原样保留。
     * </p>
     *
     * @param manifestFile 清单路径
     * @param filename     条目的 {@code filename}
     * @param status       新状态，例如 {@code Generated} 或 {@code Failed}
     * @param lastError    失败原因；为 null 时清除既有的 {@code last_error}
     * @return true 表示找到并更新了条目
     * @throws IOException 读写失败
     */
    public static boolean updateItemStatus(Path manifestFile, String filename, String status, String lastError)
            throws IOException {
        ReentrantLock lock = lockFor(manifestFile);
        lock.lock();
        try {
            Map<String, Object> manifest = read(manifestFile);
            if (!(manifest.get("items") instanceof List<?> items)) {
                return false;
            }
            boolean updated = false;
            for (Object candidate : items) {
                if (!(candidate instanceof Map<?, ?> item)) {
                    continue;
                }
                if (!filename.equals(item.get("filename"))) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> mutableItem = (Map<String, Object>) item;
                mutableItem.put("status", status);
                if (lastError == null) {
                    mutableItem.remove("last_error");
                } else {
                    mutableItem.put("last_error", truncate(lastError));
                }
                updated = true;
                break;
            }
            if (updated) {
                writeAtomically(manifestFile, manifest);
            }
            return updated;
        } finally {
            lock.unlock();
        }
    }

    private static void writeAtomically(Path manifestFile, Map<String, Object> manifest) throws IOException {
        Path parent = manifestFile.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "image-prompts-", ".json.tmp");
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), manifest);
            try {
                Files.move(temporary, manifestFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, manifestFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static ReentrantLock lockFor(Path manifestFile) {
        return LOCKS.computeIfAbsent(manifestFile.toAbsolutePath().normalize().toString(),
                key -> new ReentrantLock());
    }

    private static String truncate(String message) {
        return message.length() <= MAX_ERROR_CHARS ? message : message.substring(0, MAX_ERROR_CHARS);
    }
}
