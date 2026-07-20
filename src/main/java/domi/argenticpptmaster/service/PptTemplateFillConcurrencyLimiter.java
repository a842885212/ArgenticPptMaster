package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.TemplateFillErrorCode;
import domi.argenticpptmaster.exception.PptTemplateFillConflictException;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/** 限制同时执行的模板填充任务数量。 */
@Component
public class PptTemplateFillConcurrencyLimiter {

    private final Semaphore semaphore;

    public PptTemplateFillConcurrencyLimiter(PptMasterProperties properties) {
        int max = Math.max(1, properties.templateFillMaxConcurrentJobs());
        this.semaphore = new Semaphore(max);
    }

    public void acquire() {
        if (!semaphore.tryAcquire()) {
            throw new PptTemplateFillConflictException(
                    TemplateFillErrorCode.TEMPLATE_FILL_CONCURRENCY_LIMIT.code()
                            + ": template-fill concurrency limit reached");
        }
    }

    public void release() {
        semaphore.release();
    }
}
