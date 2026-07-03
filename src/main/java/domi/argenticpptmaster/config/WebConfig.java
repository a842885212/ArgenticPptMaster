package domi.argenticpptmaster.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置类，用于处理跨域资源共享（CORS）等相关配置。
 * <p>
 * 当前 PPT 生成前端已经支持通过独立的服务地址访问 ArgenticPptMaster，
 * 流程联调阶段不应再将来源固定为单一的 localhost 端口。因此这里改为：
 * </p>
 * <ul>
 *   <li>默认放开常见本地开发来源模式，确保联调阶段的跨域 REST / SSE 流程可用；</li>
 *   <li>同时支持通过配置项 {@code ppt.web.cors.allowed-origin-patterns}
 *       在部署时收敛允许的来源列表；</li>
 *   <li>仅对 {@code /api/**} 路径生效，避免无关路径被额外暴露。</li>
 * </ul>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 允许访问 API 的来源模式列表。
     * <p>
     * 默认值为 {@code http://localhost:*,http://127.0.0.1:*,http://[::1]:*}，
     * 用于流程可用性测试阶段快速打通本机前后端联调。
     * 生产环境建议通过外部配置显式收敛为受信任域名列表，多个值使用逗号分隔。
     * </p>
     */
    private final List<String> allowedOriginPatterns;

    /**
     * 构造跨域配置。
     *
     * @param allowedOriginPatternsProperty 允许的来源模式配置，多个值以逗号分隔
     */
    public WebConfig(
            @Value("${ppt.web.cors.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*,http://[::1]:*}")
            String allowedOriginPatternsProperty) {
        this.allowedOriginPatterns = Arrays.stream(allowedOriginPatternsProperty.split(","))
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .toList();
    }

    /**
     * 配置跨域映射。
     * <p>
     * 允许前端在独立域名/端口下访问 PPT 任务相关接口，包括：
     * </p>
     * <ul>
     *   <li>任务创建、查询、确认等普通 REST 请求；</li>
     *   <li>{@code text/event-stream} 的 SSE 事件订阅请求；</li>
     *   <li>PPT 导出文件下载请求。</li>
     * </ul>
     *
     * @param registry CorsRegistry 对象
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(HttpHeaders.CONTENT_DISPOSITION)
                .allowCredentials(true)
                .maxAge(3600);
    }
}
