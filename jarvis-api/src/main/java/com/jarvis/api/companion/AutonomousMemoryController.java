package com.jarvis.api.companion;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.memory.autonomous.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
@RestController
@RequestMapping("/api/v1/memory/autonomous")
public class AutonomousMemoryController {
    private final AutonomousMemoryService memory;private final AutonomousMemoryPipeline pipeline;
    public AutonomousMemoryController(AutonomousMemoryService memory,AutonomousMemoryPipeline pipeline){this.memory=memory;this.pipeline=pipeline;}
    private String owner(){return CurrentUserContext.requireAuthenticatedUserId();}
    @GetMapping public List<SemanticMemoryEntry> list(){return memory.list(owner());}
    @GetMapping("/status") public Map<String,Object> status(){return Map.of("extractionEnabled",pipeline.enabled(),"durableMemory",memory.durable(),"storageNamespace","autonomous-memory");}
    public record Update(long expectedRevision,String content){}
    @PutMapping("/{id}") public SemanticMemoryEntry update(@PathVariable String id,@RequestBody Update request){try{return memory.update(owner(),id,request.expectedRevision(),request.content());}catch(IllegalArgumentException e){throw error(e);}}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id){try{memory.delete(owner(),id);}catch(IllegalArgumentException e){throw error(e);}}
    private ResponseStatusException error(IllegalArgumentException e){return new ResponseStatusException(e.getMessage().contains("not found")?HttpStatus.NOT_FOUND:e.getMessage().contains("conflict")?HttpStatus.CONFLICT:HttpStatus.BAD_REQUEST,e.getMessage());}
}
