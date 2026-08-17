package com.jarvis.core.diagnostics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression coverage: build identification is a diagnostics nice-to-have, never a build/startup
 * gate. Missing {@link BuildProperties} (not generated, e.g. running outside the packaged Maven
 * build) and a missing/unavailable {@code git} binary must both degrade to logging "unknown"
 * fields, never throw during {@code ApplicationReadyEvent} handling.
 */
class BuildIdentificationLoggerTest {

    @Test
    void logBuildIdentificationNeverThrowsWhenBuildPropertiesIsUnavailable() {
        BuildIdentificationLogger logger = new BuildIdentificationLogger("2.9.0", emptyProvider());

        assertThatCode(logger::logBuildIdentification).doesNotThrowAnyException();
    }

    private ObjectProvider<BuildProperties> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public BuildProperties getObject() throws BeansException {
                throw new NoSuchBeanDefinitionException(BuildProperties.class);
            }

            @Override
            public BuildProperties getObject(Object... args) throws BeansException {
                throw new NoSuchBeanDefinitionException(BuildProperties.class);
            }

            @Override
            public BuildProperties getIfAvailable() throws BeansException {
                return null;
            }

            @Override
            public BuildProperties getIfUnique() throws BeansException {
                return null;
            }
        };
    }
}
