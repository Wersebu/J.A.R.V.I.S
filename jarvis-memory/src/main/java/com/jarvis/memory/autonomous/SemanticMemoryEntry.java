package com.jarvis.memory.autonomous;
import java.time.Instant;
import java.util.List;
public record SemanticMemoryEntry(String id,long revision,String subject,String predicate,String content,double confidence,String category,String state,String conflictWith,Instant createdAt,Instant updatedAt,Instant expiresAt,List<Source> sources) {
    public SemanticMemoryEntry {sources=List.copyOf(sources);}
    public record Source(String conversationId,String requestId,Instant timestamp){}
}
