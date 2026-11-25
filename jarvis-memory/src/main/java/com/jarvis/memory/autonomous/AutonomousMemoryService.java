package com.jarvis.memory.autonomous;

import com.fasterxml.jackson.databind.*;
import com.jarvis.common.companion.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Storage-independent fact service. New namespace; never replaces legacy semantic/knowledge stores. */
@Service
public class AutonomousMemoryService {
    public record Candidate(String decision,String sensitivity,String subject,String predicate,String content,double confidence,String category,Instant expiresAt){}
    private final CompanionDocumentStore store;private final ObjectMapper mapper;
    public AutonomousMemoryService(CompanionDocumentStore store,ObjectMapper mapper){this.store=store;this.mapper=mapper.copy().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);}
    private String canonical(String value){return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").strip();}
    private String key(String subject,String predicate){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((canonical(subject)+"\n"+canonical(predicate)).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public static boolean privateData(String text){return !PublicText.safe(text)||text.matches("(?s).*\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b.*")||text.matches("(?s).*\\b[0-9][0-9 .()-]{7,}[0-9]\\b.*");}
    public synchronized Optional<SemanticMemoryEntry> ingest(String owner,Candidate c,SemanticMemoryEntry.Source source){
        if(source==null||source.timestamp()==null||source.requestId()==null||source.requestId().isBlank()||source.conversationId()==null||source.conversationId().isBlank())return Optional.empty();
        if(c==null||!"STORE".equals(c.decision())||!"NON_SENSITIVE".equals(c.sensitivity())||!Double.isFinite(c.confidence())||c.confidence()<.8||c.confidence()>1)return Optional.empty();
        String subject=PublicText.required(c.subject(),160),predicate=PublicText.required(c.predicate(),160),content=PublicText.required(c.content(),2000);
        if(privateData(content)||privateData(subject)||privateData(predicate)||c.expiresAt()!=null&&!c.expiresAt().isAfter(Instant.now()))return Optional.empty();
        if(!Set.of("SEMANTIC","PREFERENCE","PROJECT","TEMPORARY").contains(c.category()))return Optional.empty();
        if(store.find(owner,"memory-deletion",key(subject,predicate)).isPresent()||store.find(owner,"memory-deletion-cutoff","cutoff").filter(d->!source.timestamp().isAfter(d.updatedAt())).isPresent())return Optional.empty();
        List<SemanticMemoryEntry> existing=list(owner);var same=existing.stream().filter(m->key(m.subject(),m.predicate()).equals(key(subject,predicate))&&!m.state().equals("SUPERSEDED")).toList();
        var duplicate=same.stream().filter(m->canonical(m.content()).equals(canonical(content))).findFirst();Instant now=Instant.now();
        if(duplicate.isPresent()){var m=duplicate.get();var sources=new ArrayList<>(m.sources());if(!sources.contains(source))sources.add(source);while(sources.size()>20)sources.removeFirst();var updated=new SemanticMemoryEntry(m.id(),m.revision()+1,m.subject(),m.predicate(),m.content(),Math.max(m.confidence(),c.confidence()),m.category(),m.state(),m.conflictWith(),m.createdAt(),now,m.expiresAt(),sources);save(owner,updated);audit(owner,"duplicate.sources.updated",1);return Optional.of(updated);}
        if(existing.size()>=1000)return Optional.empty();
        var conflicting=same.stream().filter(m->Set.of("ACTIVE","CONFLICT").contains(m.state())).findFirst();
        var created=new SemanticMemoryEntry(UUID.randomUUID().toString(),1,subject,predicate,content,c.confidence(),c.category(),conflicting.isPresent()?"PENDING_REVIEW":"ACTIVE",conflicting.map(SemanticMemoryEntry::id).orElse(null),now,now,c.expiresAt(),List.of(source));
        var writes=new ArrayList<CompanionDocumentStore.Document>();
        if(conflicting.isPresent()){var m=conflicting.get();writes.add(document(owner,new SemanticMemoryEntry(m.id(),m.revision()+1,m.subject(),m.predicate(),m.content(),m.confidence(),m.category(),"CONFLICT",created.id(),m.createdAt(),now,m.expiresAt(),m.sources())));}
        writes.add(document(owner,created));store.putAll(writes);audit(owner,conflicting.isPresent()?"contradiction.review.required":"memory.created",1);return Optional.of(created);
    }
    private List<SemanticMemoryEntry> records(String owner){return store.documents(owner,"autonomous-memory","").stream().map(d->decode(d.body())).toList();}
    public synchronized List<SemanticMemoryEntry> list(String owner){return records(owner).stream().filter(m->m.expiresAt()==null||m.expiresAt().isAfter(Instant.now())).toList();}
    public synchronized SemanticMemoryEntry get(String owner,String id){return store.find(owner,"autonomous-memory",id).map(d->decode(d.body())).orElseThrow(()->new IllegalArgumentException("Memory not found"));}
    public synchronized SemanticMemoryEntry update(String owner,String id,long revision,String content){var m=get(owner,id);if(m.revision()!=revision)throw new IllegalArgumentException("Memory revision conflict");content=PublicText.required(content,2000);if(privateData(content))throw new IllegalArgumentException("Sensitive memory rejected");Instant now=Instant.now();var updated=new SemanticMemoryEntry(m.id(),revision+1,m.subject(),m.predicate(),content,m.confidence(),m.category(),"ACTIVE",null,m.createdAt(),now,m.expiresAt(),m.sources());var writes=new ArrayList<CompanionDocumentStore.Document>();for(var other:list(owner))if(!other.id().equals(id)&&key(other.subject(),other.predicate()).equals(key(m.subject(),m.predicate())))writes.add(document(owner,new SemanticMemoryEntry(other.id(),other.revision()+1,other.subject(),other.predicate(),other.content(),other.confidence(),other.category(),"SUPERSEDED",id,other.createdAt(),now,other.expiresAt(),other.sources())));writes.add(document(owner,updated));store.putAll(writes);audit(owner,"memory.user.updated",1);return updated;}
    public synchronized void delete(String owner,String id){var m=get(owner,id);String fact=key(m.subject(),m.predicate());store.putAll(List.of(new CompanionDocumentStore.Document(owner,"memory-deletion",fact,"{}",Instant.now()),new CompanionDocumentStore.Document(owner,"memory-deletion-cutoff","cutoff","{}",Instant.now())));for(var other:records(owner))if(key(other.subject(),other.predicate()).equals(fact))store.delete(owner,"autonomous-memory",other.id());audit(owner,"memory.user.deleted",1);}
    public synchronized Map<String,Integer> consolidate(String owner){
        int expired=0,merged=0,contradictions=0;
        for(var m:records(owner))if(m.expiresAt()!=null&&!m.expiresAt().isAfter(Instant.now())){store.delete(owner,"autonomous-memory",m.id());expired++;}
        var groups=new HashMap<String,List<SemanticMemoryEntry>>();for(var m:list(owner))if(m.state().equals("ACTIVE"))groups.computeIfAbsent(key(m.subject(),m.predicate()),k->new ArrayList<>()).add(m);
        for(var group:groups.values()){
            if(group.size()<2)continue;
            var byContent=new LinkedHashMap<String,List<SemanticMemoryEntry>>();for(var m:group)byContent.computeIfAbsent(canonical(m.content()),k->new ArrayList<>()).add(m);
            for(var duplicates:byContent.values())if(duplicates.size()>1){var first=duplicates.getFirst();var sources=new LinkedHashSet<>(first.sources());for(int i=1;i<duplicates.size();i++)sources.addAll(duplicates.get(i).sources());var kept=sources.stream().limit(20).toList();save(owner,new SemanticMemoryEntry(first.id(),first.revision()+1,first.subject(),first.predicate(),first.content(),first.confidence(),first.category(),first.state(),null,first.createdAt(),Instant.now(),first.expiresAt(),kept));for(int i=1;i<duplicates.size();i++){store.delete(owner,"autonomous-memory",duplicates.get(i).id());merged++;}}
            if(byContent.size()>1){for(var duplicates:byContent.values()){var m=get(owner,duplicates.getFirst().id());save(owner,new SemanticMemoryEntry(m.id(),m.revision()+1,m.subject(),m.predicate(),m.content(),m.confidence(),m.category(),"CONFLICT",null,m.createdAt(),Instant.now(),m.expiresAt(),m.sources()));}contradictions++;}
        }
        audit(owner,"dream.consolidation",expired+merged+contradictions);return Map.of("expired",expired,"merged",merged,"contradictions",contradictions);
    }
    public Set<String> owners(){var owners=new HashSet<String>();store.all("autonomous-memory").forEach(d->owners.add(d.owner()));return Set.copyOf(owners);}
    public boolean durable(){return store.durable();}
    private void save(String owner,SemanticMemoryEntry entry){store.put(document(owner,entry));}
    private CompanionDocumentStore.Document document(String owner,SemanticMemoryEntry entry){try{return new CompanionDocumentStore.Document(owner,"autonomous-memory",entry.id(),mapper.writeValueAsString(entry),entry.updatedAt());}catch(Exception e){throw new IllegalStateException("Memory serialization failed",e);}}
    private SemanticMemoryEntry decode(String body){try{return mapper.readValue(body,SemanticMemoryEntry.class);}catch(Exception e){throw new IllegalStateException("Memory storage corrupt",e);}}
    private void audit(String owner,String operation,int count){try{store.put(new CompanionDocumentStore.Document(owner,"memory-audit",UUID.randomUUID().toString(),mapper.writeValueAsString(Map.of("operation",operation,"count",count,"timestamp",Instant.now())),Instant.now()));var entries=store.documents(owner,"memory-audit","").stream().sorted(Comparator.comparing(CompanionDocumentStore.Document::updatedAt)).toList();for(int i=0;i<entries.size()-200;i++)store.delete(owner,"memory-audit",entries.get(i).id());}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
}
