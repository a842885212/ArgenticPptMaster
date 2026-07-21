package domi.argenticpptmaster.security;

import java.util.Optional;

/**
 * 解析当前请求的可信访问上下文。无认证主体时必须返回 empty（fail closed）。
 */
public interface PptAccessContextResolver {

    Optional<PptAccessContext> resolve();
}
