package com.jarvis.api.controller;

import com.jarvis.api.dto.MemoryReindexResponse;
import com.jarvis.common.memory.MemoryRecord;
import com.jarvis.common.memory.MemorySearchResult;
import com.jarvis.memory.cognitive.CognitiveMemoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for cognitive memory operations.
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private final CognitiveMemoryService memoryService;

    /**
     * Creates the memory controller.
     *
     * @param memoryService cognitive memory service
     */
    public MemoryController(CognitiveMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * Lists all long-term memories.
     *
     * @return memories
     */
    @GetMapping
    public List<MemoryRecord> list() {
        return memoryService.listAll();
    }

    /**
     * Searches memories.
     *
     * @param query query text
     * @return search result
     */
    @GetMapping("/search")
    public MemorySearchResult search(@RequestParam("query") String query) {
        return memoryService.search(query);
    }

    /**
     * Rebuilds memory indexes.
     *
     * @return reindex response
     */
    @PostMapping("/reindex")
    public MemoryReindexResponse reindex() {
        return new MemoryReindexResponse("indexed", memoryService.reindex());
    }

    /**
     * Deletes a memory.
     *
     * @param id memory identifier
     * @return empty response
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (memoryService.delete(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
