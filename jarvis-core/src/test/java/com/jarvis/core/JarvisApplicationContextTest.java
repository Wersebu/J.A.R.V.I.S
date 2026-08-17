package com.jarvis.core;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Regression test proving the full Spring bean graph wires up cleanly - i.e. every
 * {@code @Service}/{@code @Component} constructor is unambiguously autowireable.
 *
 * <p>This is deliberately the only test in the whole project that boots the real Spring context:
 * every other test in this codebase hand-constructs the class under test with fakes, by design,
 * for speed. That means a bean-wiring mistake - e.g. a class with more than one constructor where
 * none is marked {@code @Autowired}, so Spring falls back to requiring (and not finding) a no-arg
 * constructor - was never caught by {@code mvn test} before this test existed, only by actually
 * starting the packaged application. This test exists specifically to catch that failure mode in
 * CI instead of at deploy time.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JarvisApplicationContextTest {

    @Test
    void contextLoads() {
        // Intentionally empty: the assertion is that the ApplicationContext started at all -
        // every bean in the com.jarvis component-scan graph was successfully constructed.
    }
}
