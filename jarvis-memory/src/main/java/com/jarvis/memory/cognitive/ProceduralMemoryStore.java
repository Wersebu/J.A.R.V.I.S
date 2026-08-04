package com.jarvis.memory.cognitive;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores reusable procedures.
 */
public interface ProceduralMemoryStore {

    /**
     * Saves a procedure.
     *
     * @param record procedure record
     */
    void save(ProceduralMemoryRecord record);

    /**
     * Updates a procedure.
     *
     * @param record procedure record
     */
    void update(ProceduralMemoryRecord record);

    /**
     * Finds a procedure by name.
     *
     * @param name procedure name
     * @return existing procedure
     */
    Optional<ProceduralMemoryRecord> findByName(String name);

    /**
     * Searches procedures.
     *
     * @param query query text
     * @param limit maximum number of results
     * @return matching procedures
     */
    List<ProceduralMemoryRecord> search(String query, int limit);

    /**
     * Lists all procedures.
     *
     * @return procedures
     */
    List<ProceduralMemoryRecord> listAll();

    /**
     * Deletes a procedure.
     *
     * @param id memory identifier
     * @return true if deleted
     */
    boolean delete(UUID id);
}
