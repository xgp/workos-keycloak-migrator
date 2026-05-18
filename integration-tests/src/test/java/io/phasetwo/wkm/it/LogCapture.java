package io.phasetwo.wkm.it;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;

/**
 * In-JVM logback appender used to assert what the migrator logged on a given run. Attach before
 * the action under test, then read events. Detach in a finally to avoid leaks across tests.
 */
public class LogCapture implements AutoCloseable {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final ch.qos.logback.classic.Logger logger;
    private final Level originalLevel;

    public LogCapture(String loggerName) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.logger = ctx.getLogger(loggerName);
        this.originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender.setContext(ctx);
        appender.start();
        logger.addAppender(appender);
    }

    public List<ILoggingEvent> events() {
        synchronized (appender.list) {
            return List.copyOf(appender.list);
        }
    }

    public Stream<String> messages() {
        return events().stream().map(ILoggingEvent::getFormattedMessage);
    }

    public void reset() {
        synchronized (appender.list) {
            appender.list.clear();
        }
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }
}
