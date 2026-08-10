package com.jarvis.api.diagnostics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Logback appender that mirrors J.A.R.V.I.S. backend logs to the realtime UI console.
 */
@Component
public class JarvisLogWebSocketAppender extends AppenderBase<ILoggingEvent> implements SmartLifecycle {

    private static final String APPENDER_NAME = "JARVIS_WEBSOCKET_LOG_APPENDER";
    private static final int MAX_THROWABLE_CHARS = 12_000;
    private volatile boolean running;
    private Logger rootLogger;

    /**
     * Attaches the appender to the active Logback root logger.
     */
    @Override
    public void start() {
        if (running) {
            return;
        }
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        setContext(context);
        setName(APPENDER_NAME);
        rootLogger.detachAppender(APPENDER_NAME);
        super.start();
        rootLogger.addAppender(this);
        running = true;
    }

    /**
     * Detaches the appender from Logback.
     */
    @Override
    public void stop() {
        if (!running) {
            return;
        }
        if (rootLogger != null) {
            rootLogger.detachAppender(this);
        }
        running = false;
        super.stop();
    }

    /**
     * Returns whether the appender is active.
     *
     * @return true when active
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Keeps the appender active until the application stops.
     *
     * @return maximum stop phase
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null || !isJarvisLog(event) || event.getLevel().isGreaterOrEqual(Level.TRACE) == false) {
            return;
        }
        JarvisLogBroadcaster.publish(JarvisLogEvent.of(
                Instant.ofEpochMilli(event.getTimeStamp()),
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getThreadName(),
                event.getFormattedMessage(),
                throwable(event.getThrowableProxy())
        ));
    }

    private boolean isJarvisLog(ILoggingEvent event) {
        String loggerName = event.getLoggerName();
        return loggerName != null && loggerName.startsWith("com.jarvis");
    }

    private String throwable(IThrowableProxy throwableProxy) {
        if (throwableProxy == null) {
            return "";
        }
        String stackTrace = ThrowableProxyUtil.asString(throwableProxy);
        return stackTrace.length() <= MAX_THROWABLE_CHARS
                ? stackTrace
                : stackTrace.substring(0, MAX_THROWABLE_CHARS) + "\n... stack trace truncated";
    }
}
