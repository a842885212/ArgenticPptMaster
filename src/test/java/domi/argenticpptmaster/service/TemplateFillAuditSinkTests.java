package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class TemplateFillAuditSinkTests {

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        Logger logger = (Logger) LoggerFactory.getLogger("template-fill-audit");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger("template-fill-audit");
        logger.detachAppender(appender);
    }

    @Test
    void recordsStructuredAuditWithoutRawIdentity() {
        UUID jobId = UUID.randomUUID();
        new TemplateFillAuditSink().record(
                "download",
                jobId,
                TemplateFillLifecycleStore.digestSubject("user-1"),
                TemplateFillLifecycleStore.digestTenant("tenant-a"),
                "SUCCESS",
                "OK");

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).contains("download", jobId.toString(), "SUCCESS", "OK");
        assertThat(message).doesNotContain("user-1", "tenant-a");
    }
}
