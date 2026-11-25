package com.jarvis.common.companion;
import java.time.Instant;
import java.util.*;
/** Storage boundary; payloads contain public state only, never task prompts or hidden reasoning. */
public interface CompanionDocumentStore {
    record Document(String owner,String kind,String id,String body,Instant updatedAt) {}
    boolean durable();
    List<Document> all(String kind);
    Optional<Document> find(String owner,String kind,String id);
    void put(Document document);
    default void putAll(List<Document> documents){documents.forEach(this::put);}
    default void deleteTask(String owner,String id){delete(owner,"task",id);delete(owner,"plan",id);deletePrefix(owner,"plan-revision",id+"/");deletePrefix(owner,"evidence",id+"/");}
    void delete(String owner,String kind,String id);
    void deletePrefix(String owner,String kind,String prefix);
}
