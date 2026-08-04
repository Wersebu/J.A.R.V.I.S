package com.jarvis.memory.cognitive;

import java.util.List;
import java.util.UUID;

/**
 * Stores episodic memory events.
 */
public interface EpisodicMemoryStore {

    /**
     * Saves an event.
     *
     * @param record event record
     */
    void save(EpisodicMemoryRecord record);

    /**
     * Searches events.
     *
     * @param query query text
     * @param limit maximum number of results
     * @return matching events
     */
    List<EpisodicMemoryRecord> search(String query, int limit);

    /**
     * Lists all events.
     *
     * @return events
     */
    List<EpisodicMemoryRecord> listAll();

    /**
     * Deletes an event.
     *
     * @param id memory identifier
     * @return true if deleted
     */
    boolean delete(UUID id);
}
