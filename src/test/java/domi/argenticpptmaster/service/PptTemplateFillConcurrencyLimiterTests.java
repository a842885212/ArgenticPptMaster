package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.TemplateFillErrorCode;
import domi.argenticpptmaster.exception.PptTemplateFillConflictException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PptTemplateFillConcurrencyLimiterTests {

    @Test
    void rejectsWhenConcurrencySaturated() {
        PptTemplateFillConcurrencyLimiter limiter = new PptTemplateFillConcurrencyLimiter(
                new PptMasterProperties(Path.of("repo"), Path.of("workspace"), "python3",
                        java.time.Duration.ofMinutes(1), null, 1024, 0, 0, 0, 1, null));

        limiter.acquire();

        assertThatThrownBy(limiter::acquire)
                .isInstanceOf(PptTemplateFillConflictException.class)
                .hasMessageContaining(TemplateFillErrorCode.TEMPLATE_FILL_CONCURRENCY_LIMIT.code());

        limiter.release();
        assertThatCodeSafeAcquire(limiter);
    }

    private void assertThatCodeSafeAcquire(PptTemplateFillConcurrencyLimiter limiter) {
        limiter.acquire();
        limiter.release();
        assertThat(true).isTrue();
    }
}
