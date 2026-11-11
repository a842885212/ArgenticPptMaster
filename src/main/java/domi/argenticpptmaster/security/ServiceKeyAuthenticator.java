package domi.argenticpptmaster.security;

import domi.argenticpptmaster.config.ServiceAuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 校验 {@code X-PPT-Service-Key} 并解析稳定 {@code clientId}。
 */
@Component
public class ServiceKeyAuthenticator {

    public static final String HEADER = "X-PPT-Service-Key";
    public static final String REQUEST_ATTR_CLIENT_ID = "ppt.serviceAuth.clientId";

    private final ServiceAuthProperties properties;

    public ServiceKeyAuthenticator(ServiceAuthProperties properties) {
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * @return 匹配的 clientId；密钥缺失或不匹配时 empty
     */
    public Optional<String> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        byte[] presented = presentedKey.getBytes(StandardCharsets.UTF_8);
        for (ServiceAuthProperties.Client client : properties.clients()) {
            byte[] expected = client.key().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(presented, expected)) {
                return Optional.of(client.id());
            }
        }
        return Optional.empty();
    }
}
