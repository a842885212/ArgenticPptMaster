package domi.argenticpptmaster.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 从当前请求属性读取服务密钥鉴权得到的 {@code clientId}。
 * <p>
 * 无请求上下文或未写入 clientId 时返回 empty（fail closed）。
 * 当 local-access 提供了 {@link PptAccessContextResolver} bean 时本组件不注册。
 * </p>
 */
@Component
@ConditionalOnMissingBean(PptAccessContextResolver.class)
public class RequestAttributePptAccessContextResolver implements PptAccessContextResolver {

    @Override
    public Optional<PptAccessContext> resolve() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return Optional.empty();
        }
        HttpServletRequest request = servletAttributes.getRequest();
        Object clientId = request.getAttribute(ServiceKeyAuthenticator.REQUEST_ATTR_CLIENT_ID);
        if (!(clientId instanceof String id) || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(PptAccessContext.user(id, id, Set.of()));
    }
}
