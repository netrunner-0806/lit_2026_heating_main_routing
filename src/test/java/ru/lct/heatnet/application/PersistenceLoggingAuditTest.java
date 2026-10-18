package ru.lct.heatnet.application;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import ru.lct.heatnet.config.HeatnetProperties;
import ru.lct.heatnet.persistence.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class PersistenceLoggingAuditTest {
    @Test void failedProgressAndTerminalWritesAreVisibleInLogs() {
        var repo=mock(JobRepository.class);when(repo.save(any())).thenThrow(new IllegalStateException("database unavailable"));
        var runner=new JobRunner(repo,new HeatnetProperties());var e=new JobEntity();e.setId("logging-test");
        var appender=new ListAppender<ILoggingEvent>();appender.start();
        Logger logger=(Logger)LoggerFactory.getLogger(JobRunner.class);logger.addAppender(appender);
        try {
            ReflectionTestUtils.invokeMethod(runner,"progress",e,"running");
            assertTrue(appender.list.stream().anyMatch(x->x.getFormattedMessage().contains("logging-test") && x.getThrowableProxy()!=null));
            appender.list.clear();
            assertThrows(RuntimeException.class,()->ReflectionTestUtils.invokeMethod(runner,"fail",e,"failed",null,0L));
            assertTrue(appender.list.stream().anyMatch(x->x.getFormattedMessage().contains("logging-test") && x.getThrowableProxy()!=null));
        } finally { logger.detachAppender(appender); }
    }
}
