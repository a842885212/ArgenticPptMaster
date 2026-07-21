package domi.argenticpptmaster.security;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用可注入访问上下文解析器。
 */
public class FixedPptAccessContextResolver implements PptAccessContextResolver {

    private final AtomicReference<PptAccessContext> context = new AtomicReference<>();

    public FixedPptAccessContextResolver() {
    }

    public FixedPptAccessContextResolver(PptAccessContext context) {
        this.context.set(context);
    }

    public void set(PptAccessContext context) {
        this.context.set(context);
    }

    public void clear() {
        this.context.set(null);
    }

    @Override
    public Optional<PptAccessContext> resolve() {
        return Optional.ofNullable(context.get());
    }
}
