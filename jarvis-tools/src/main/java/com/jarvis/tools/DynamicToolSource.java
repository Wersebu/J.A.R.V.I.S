package com.jarvis.tools;

import java.util.List;

/**
 * Supplies tools that are discovered at runtime instead of being static Spring beans.
 */
public interface DynamicToolSource {

    /**
     * Returns the currently available dynamic tools.
     *
     * @return runtime-discovered tools
     */
    List<JarvisTool> tools();
}
