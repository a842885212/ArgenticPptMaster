package domi.argenticpptmaster.config;

import domi.argenticpptmaster.security.FixedPptAccessContextResolver;
import domi.argenticpptmaster.security.PptAccessContext;
import domi.argenticpptmaster.security.PptAccessContextResolver;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 开发期固定访问上下文：无需接入 JWT/网关鉴权。
 * <p>
 * 由 {@code ppt-master.local-access.enabled} 控制（默认 false，生产 fail closed）。
 * 本地开发可显式开启；上线鉴权后保持 {@code false}，由服务密钥 Filter 写入 client 上下文。
 * </p>
 */
@Configuration
@ConditionalOnProperty(prefix = "ppt-master.local-access", name = "enabled", havingValue = "true", matchIfMissing = false)
public class LocalPptAccessContextConfiguration {

    @Bean
    PptAccessContextResolver pptAccessContextResolver(
            @Value("${ppt-master.local-access.subject-id:dev-user}") String subjectId,
            @Value("${ppt-master.local-access.tenant-id:dev-tenant}") String tenantId,
            @Value("${ppt-master.local-access.roles:ADMIN}") String rolesCsv) {
        Set<String> roles = Arrays.stream(rolesCsv.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return new FixedPptAccessContextResolver(PptAccessContext.user(subjectId, tenantId, roles));
    }
}
