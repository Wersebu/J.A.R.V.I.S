package com.jarvis.core.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Logs a startup banner identifying exactly which build/commit/branch is running, so a live report
 * can always be matched back to "is this fix actually deployed yet" from the server logs alone.
 *
 * <p>{@link BuildProperties} is populated by Spring Boot's {@code build-info} Maven goal (an
 * optional bean, present only when generated at build time). Git commit/branch are read at startup
 * by invoking the {@code git} CLI directly against the working directory, rather than through a
 * build-time Maven plugin - this keeps build identification independent of network access to the
 * Maven plugin repository. Every value falls back to {@code "unknown"} on any failure (missing
 * bean, missing {@code git} binary, not a git checkout, timeout, ...); none of this may ever fail
 * application startup.</p>
 */
@Component
public class BuildIdentificationLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(BuildIdentificationLogger.class);
    private static final String UNKNOWN = "unknown";
    private static final long GIT_COMMAND_TIMEOUT_SECONDS = 3;

    private final String version;
    private final ObjectProvider<BuildProperties> buildProperties;

    /**
     * Creates the logger.
     *
     * @param version public Jarvis backend version
     * @param buildProperties optional build metadata, present only when generated at build time
     */
    public BuildIdentificationLogger(
            @Value("${jarvis.version}") String version,
            ObjectProvider<BuildProperties> buildProperties
    ) {
        this.version = version;
        this.buildProperties = buildProperties;
    }

    /**
     * Logs the build identification banner once the application is fully started.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logBuildIdentification() {
        String buildTime = safe(() -> buildProperties.getIfAvailable().getTime().toString());
        String commit = safe(() -> runGitCommand("rev-parse", "--short", "HEAD"));
        String branch = safe(() -> runGitCommand("rev-parse", "--abbrev-ref", "HEAD"));
        LOGGER.info("""

                ==========================================
                J.A.R.V.I.S. Core
                Version: {}
                Git commit: {}
                Branch: {}
                Build time: {}
                ==========================================""",
                version, commit, branch, buildTime);
    }

    private String safe(Supplier<String> supplier) {
        try {
            String value = supplier.get();
            return value == null || value.isBlank() ? UNKNOWN : value;
        } catch (RuntimeException exception) {
            return UNKNOWN;
        }
    }

    private String runGitCommand(String... args) {
        try {
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add("git");
            command.addAll(java.util.List.of(args));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            boolean finished = process.waitFor(GIT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? output : null;
        } catch (IOException exception) {
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
