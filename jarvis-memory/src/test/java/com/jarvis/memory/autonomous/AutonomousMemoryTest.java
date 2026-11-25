package com.jarvis.memory.autonomous;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.companion.InMemoryCompanionStore;
import com.jarvis.common.ai.*;
import com.jarvis.memory.job.MemoryJob;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AutonomousMemoryTest {
    private final SemanticMemoryEntry.Source source=new SemanticMemoryEntry.Source("conversation","request",Instant.now());
    private AutonomousMemoryService.Candidate candidate(String content){return new AutonomousMemoryService.Candidate("STORE","NON_SENSITIVE","user","community_role",content,.95,"PROJECT",null);}
    @Test void duplicatesContradictionsExplicitUpdateDeletionAndOwnerIsolation(){
        var memory=new AutonomousMemoryService(new InMemoryCompanionStore(),new ObjectMapper());var first=memory.ingest("alice",candidate("Moderator in NosGalaxy"),source).orElseThrow();var duplicate=memory.ingest("alice",candidate("MODERATOR IN NOSGALAXY"),source).orElseThrow();assertEquals(first.id(),duplicate.id());assertEquals(1,memory.list("alice").size());
        var conflicting=memory.ingest("alice",candidate("Administrator in NosGalaxy"),source).orElseThrow();assertEquals("PENDING_REVIEW",conflicting.state());assertEquals("CONFLICT",memory.get("alice",first.id()).state());assertEquals("Moderator in NosGalaxy",memory.get("alice",first.id()).content());assertTrue(memory.list("bob").isEmpty());assertThrows(IllegalArgumentException.class,()->memory.delete("bob",first.id()));
        var current=memory.get("alice",conflicting.id());var updated=memory.update("alice",current.id(),current.revision(),"Confirmed administrator in NosGalaxy");assertEquals("ACTIVE",updated.state());assertEquals("SUPERSEDED",memory.get("alice",first.id()).state());
        memory.delete("alice",updated.id());assertTrue(memory.list("alice").isEmpty());assertTrue(memory.ingest("alice",candidate("Moderator in NosGalaxy"),source).isEmpty());
    }
    @Test void sensitiveDataAndMissingSourcesFailClosed(){
        var memory=new AutonomousMemoryService(new InMemoryCompanionStore(),new ObjectMapper());assertTrue(memory.ingest("alice",candidate("user@example.test"),source).isEmpty());
        assertThrows(IllegalArgumentException.class,()->memory.ingest("alice",candidate("password: super-secret-value"),source));
        assertTrue(memory.ingest("alice",new AutonomousMemoryService.Candidate("STORE","SENSITIVE","user","health","private health fact",.99,"SEMANTIC",null),source).isEmpty());assertTrue(memory.ingest("alice",candidate("A project fact"),null).isEmpty());assertTrue(memory.list("alice").isEmpty());
    }
    @Test void semanticExtractionWithoutRememberKeywordAndPrivacyGuard()throws Exception {
        var memory=new AutonomousMemoryService(new InMemoryCompanionStore(),new ObjectMapper());var provider=mock(AIProvider.class);when(provider.provider()).thenReturn("mock");when(provider.chat(any(),anyString(),eq(AIJobType.MEMORY_AGENT))).thenReturn(new com.jarvis.common.dto.ChatResponse("{\"memories\":[{\"decision\":\"STORE\",\"sensitivity\":\"NON_SENSITIVE\",\"subject\":\"user\",\"predicate\":\"community_role\",\"content\":\"Moderator in NosGalaxy\",\"confidence\":0.95,\"category\":\"PROJECT\",\"expiresAt\":null}]}"));
        try(var pipeline=new AutonomousMemoryPipeline(memory,new ObjectMapper(),List.of(provider),true,"mock","test")){
            var job=new MemoryJob(UUID.randomUUID(),"r1","c1","I became a moderator in NosGalaxy.","",Instant.now(),List.of());assertTrue(pipeline.submit("alice",job));long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);while(memory.list("alice").isEmpty()&&System.nanoTime()<end)Thread.sleep(5);assertEquals(1,memory.list("alice").size());
            assertFalse(pipeline.submit("alice",new MemoryJob(UUID.randomUUID(),"r2","c1","password: must-not-be-stored","",Instant.now(),List.of())));verify(provider,times(1)).chat(any(),anyString(),eq(AIJobType.MEMORY_AGENT));
        }
    }
    @Test void expirationAndOptInDefault(){
        var store=new InMemoryCompanionStore();var memory=new AutonomousMemoryService(store,new ObjectMapper());assertTrue(memory.ingest("alice",new AutonomousMemoryService.Candidate("STORE","NON_SENSITIVE","user","temporary","Expired",.9,"TEMPORARY",Instant.now().minusSeconds(1)),source).isEmpty());
        try(var pipeline=new AutonomousMemoryPipeline(memory,new ObjectMapper(),List.of(),false,"mock","test")){assertFalse(pipeline.submit("alice",new MemoryJob(UUID.randomUUID(),"r","c","A durable project fact","",Instant.now(),List.of())));}
        assertEquals(0,memory.consolidate("alice").get("expired"));
    }
    @Test void dreamingMergesOnlyActualDuplicatesAndPreservesReviewableContradictions()throws Exception {
        var store=new InMemoryCompanionStore();var mapper=new ObjectMapper().findAndRegisterModules();var memory=new AutonomousMemoryService(store,mapper);var first=memory.ingest("alice",candidate("Moderator"),source).orElseThrow();
        var duplicate=new SemanticMemoryEntry(UUID.randomUUID().toString(),1,first.subject(),first.predicate(),"MODERATOR",.9,"PROJECT","ACTIVE",null,Instant.now(),Instant.now(),null,List.of(source));
        store.put(new com.jarvis.common.companion.CompanionDocumentStore.Document("alice","autonomous-memory",duplicate.id(),mapper.writeValueAsString(duplicate),Instant.now()));
        assertEquals(1,memory.consolidate("alice").get("merged"));assertEquals(1,memory.list("alice").size());
        var conflict=new SemanticMemoryEntry(UUID.randomUUID().toString(),1,first.subject(),first.predicate(),"Administrator",.9,"PROJECT","ACTIVE",null,Instant.now(),Instant.now(),null,List.of(source));
        store.put(new com.jarvis.common.companion.CompanionDocumentStore.Document("alice","autonomous-memory",conflict.id(),mapper.writeValueAsString(conflict),Instant.now()));
        assertEquals(1,memory.consolidate("alice").get("contradictions"));assertTrue(memory.list("alice").stream().allMatch(m->m.state().equals("CONFLICT")));
    }

}
