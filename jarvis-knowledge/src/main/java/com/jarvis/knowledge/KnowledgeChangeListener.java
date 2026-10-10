package com.jarvis.knowledge;

import java.nio.file.Path;

/**
 * Notified whenever something under the knowledge root may have changed (file watcher event,
 * workspace write, index rebuild). Implementations must return quickly; heavy work is debounced.
 */
public interface KnowledgeChangeListener {

    /**
     * Signals a change.
     *
     * @param path changed path, or the knowledge root for bulk changes
     */
    void onKnowledgeChanged(Path path);
}
