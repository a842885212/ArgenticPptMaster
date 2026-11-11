package domi.argenticpptmaster.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调用方服务密钥鉴权配置。
 * <p>
 * 前缀 {@code ppt-master.service-auth}。启用后对外 {@code /api/ppt-jobs/**}
 * 必须携带匹配的 {@code X-PPT-Service-Key}。
 * </p>
 */
@ConfigurationProperties(prefix = "ppt-master.service-auth")
public record ServiceAuthProperties(
        boolean enabled,
        List<Client> clients) {

    public ServiceAuthProperties {
        if (clients == null) {
            clients = List.of();
        } else {
            List<Client> normalized = new ArrayList<>();
            for (Client client : clients) {
                if (client == null) {
                    continue;
                }
                String id = client.id() == null ? "" : client.id().trim();
                String key = client.key() == null ? "" : client.key();
                if (id.isEmpty() || key.isBlank()) {
                    continue;
                }
                normalized.add(new Client(id, key));
            }
            clients = List.copyOf(normalized);
        }
    }

    public static ServiceAuthProperties disabled() {
        return new ServiceAuthProperties(false, List.of());
    }

    /** 单个调用方：稳定 clientId + 明文密钥（仅存服务端）。 */
    public record Client(String id, String key) {
    }
}
