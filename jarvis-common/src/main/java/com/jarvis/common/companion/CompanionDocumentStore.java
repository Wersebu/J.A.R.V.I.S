package com.jarvis.common.companion;
import java.time.Instant;
import java.util.*;
/** Owner-scoped Core storage. Private task-input/tool-output kinds must not be exported as public snapshots. No hidden reasoning is stored. */
public interface CompanionDocumentStore {
    record Document(String owner,String kind,String id,String body,Instant updatedAt) {}
    boolean durable();
    List<Document> all(String kind);
    default List<Document> documents(String owner,String kind,String prefix){return all(kind).stream().filter(d->d.owner().equals(owner)&&d.id().startsWith(prefix)).toList();}
    Optional<Document> find(String owner,String kind,String id);
    void put(Document document);
    default void putAll(List<Document> documents){documents.forEach(this::put);}
    default void deleteTask(String owner,String id){delete(owner,"task",id);delete(owner,"task-input",id);delete(owner,"task-delivery","task-result/"+id);delete(owner,"plan",id);deletePrefix(owner,"plan-revision",id+"/");deletePrefix(owner,"evidence",id+"/");}
    void delete(String owner,String kind,String id);
    void deletePrefix(String owner,String kind,String prefix);
}
