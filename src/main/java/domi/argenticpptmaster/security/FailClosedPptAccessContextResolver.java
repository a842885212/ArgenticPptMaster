package domi.argenticpptmaster.security;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 默认 fail-closed 解析器：无其他 {@link PptAccessContextResolver} bean 时不授予任何主体。
 */
@Component
@ConditionalOnMissingBean(PptAccessContextResolver.class)
public class FailClosedPptAccessContextResolver implements PptAccessContextResolver {

    @Override
    public Optional<PptAccessContext> resolve() {
        return Optional.empty();
    }
}
