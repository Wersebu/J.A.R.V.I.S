package com.jarvis.common.companion;
import java.util.*;
public class InMemoryCompanionStore implements CompanionDocumentStore {
    private final Map<List<String>,Document> documents=new LinkedHashMap<>();
    public synchronized void putAll(List<Document> docs){CompanionDocumentStore.super.putAll(docs);}
    public synchronized void deleteTask(String owner,String id){CompanionDocumentStore.super.deleteTask(owner,id);}
    public boolean durable(){return false;}
    public synchronized List<Document> all(String kind){return documents.values().stream().filter(d->d.kind().equals(kind)).toList();}
    public synchronized Optional<Document> find(String owner,String kind,String id){return Optional.ofNullable(documents.get(List.of(owner,kind,id)));}
    public synchronized void put(Document d){documents.put(List.of(d.owner(),d.kind(),d.id()),d);}
    public synchronized void delete(String owner,String kind,String id){documents.remove(List.of(owner,kind,id));}
    public synchronized void deletePrefix(String owner,String kind,String prefix){documents.values().removeIf(d->d.owner().equals(owner)&&d.kind().equals(kind)&&d.id().startsWith(prefix));}
}
