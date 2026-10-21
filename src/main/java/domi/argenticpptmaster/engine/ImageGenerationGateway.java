package domi.argenticpptmaster.engine;

import java.nio.file.Path;
import java.time.Duration;

/**
 * AI image generation from a prompts manifest.
 *
 * <p>生产权威是服务内 {@code JavaImageGenerationGateway}（OpenAI 兼容直连）；
 * {@code PythonImageGenerationGateway} 仅在 {@code ppt.image.python-fallback=true} 时回落。
 * 两种实现共用清单语义：只处理 {@code Pending}/{@code Failed}，逐条回写状态，
 * 存在失败条目时返回非成功 {@link EngineResult}。</p>
 */
public interface ImageGenerationGateway {

    EngineResult generateFromManifest(Path manifest, Path outputDir, Duration timeout);
}
