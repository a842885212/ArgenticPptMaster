package domi.argenticpptmaster.security;

import java.util.Optional;

/**
 * Fail-closed 解析器：不授予任何主体。供测试与无请求上下文的回退构造使用。
 * <p>
 * 运行时默认由 {@link RequestAttributePptAccessContextResolver} 在无 local-access 时注册。
 * </p>
 */
public class FailClosedPptAccessContextResolver implements PptAccessContextResolver {

    @Override
    public Optional<PptAccessContext> resolve() {
        return Optional.empty();
    }
}
