package domi.argenticpptmaster.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 对 {@code /api/ppt-jobs/**} 强制服务密钥鉴权（当 service-auth 启用时）。
 */
@Component
@ConditionalOnProperty(prefix = "ppt-master.service-auth", name = "enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ServiceKeyAuthFilter extends OncePerRequestFilter {

    private final ServiceKeyAuthenticator authenticator;

    public ServiceKeyAuthFilter(ServiceKeyAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!authenticator.enabled()) {
            return true;
        }
        String path = request.getRequestURI();
        String context = request.getContextPath() == null ? "" : request.getContextPath();
        String relative = path.startsWith(context) ? path.substring(context.length()) : path;
        return !relative.startsWith("/api/ppt-jobs");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String presented = request.getHeader(ServiceKeyAuthenticator.HEADER);
        Optional<String> clientId = authenticator.authenticate(presented);
        if (clientId.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            // 故意不回显所提交密钥
            response.getWriter().write("{\"message\":\"service authentication required\"}");
            return;
        }
        request.setAttribute(ServiceKeyAuthenticator.REQUEST_ATTR_CLIENT_ID, clientId.get());
        filterChain.doFilter(request, response);
    }
}
