package com.jarvis.api.companion;
import com.jarvis.common.auth.CurrentUserContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;
@RestController
@RequestMapping("/api/v1/companion")
public class CompanionController {
    private final CompanionTasks tasks;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.jarvis.tools.runtime.ToolOutputStore outputs;
    @org.springframework.beans.factory.annotation.Value("${jarvis.companion.provider-mode:LIVE}") private String providerMode="LIVE";
    public CompanionController(CompanionTasks tasks){this.tasks=tasks;}
    private String owner(){return CurrentUserContext.requireAuthenticatedUserId();}
    @GetMapping("/capabilities") public Map<String,Object> capabilities(){var result=new LinkedHashMap<String,Object>(Map.of("contractVersion",CompanionTasks.version(),"tasks",true,"voiceStreaming",false,"durableHistory",tasks.durableHistory(),"eventRetention",512,"maxActiveTasks",128,"providerMode",providerMode,"taskPlans",true,"planEvidenceRequired",true));result.put("inferenceTelemetry",true);result.put("asyncDelegation",true);result.put("taskInstructions",true);result.put("autonomousResults",true);result.put("taskDependencies",true);result.put("resultArtifacts",true);result.put("partialResults",true);result.put("semanticOrchestration",true);result.put("durableEvents",true);result.put("lifecycleState",true);result.put("interactiveChat",Map.of("requestIds",true,"multiplexing",true,"sameConversationPolicy","FIFO","requestControls",true,"requestReplay",true,"protocolRevision","1.6.0"));return result;}
    @PostMapping("/tasks") @ResponseStatus(HttpStatus.ACCEPTED) public Map<String,Object> submit(@RequestBody CompanionTasks.Request request){return tasks.wire(owner(),tasks.submit(owner(),request));}
    @GetMapping("/tasks") public List<Map<String,Object>> list(){String owner=owner();return tasks.list(owner).stream().map(t->tasks.wire(owner,t)).toList();}
    @GetMapping("/tasks/{id}") public Map<String,Object> get(@PathVariable String id){return tasks.wire(owner(),tasks.get(owner(),id));}
    @GetMapping("/tasks/{id}/info") public Map<String,Object> info(@PathVariable String id){return tasks.info(owner(),id);}
    @GetMapping(value="/tasks/{id}/result",produces="text/plain;charset=UTF-8") public String result(@PathVariable String id){return Objects.toString(tasks.get(owner(),id).result(),"");}
    @GetMapping(value="/outputs/{id}",produces="text/plain;charset=UTF-8") public String output(@PathVariable String id){owner();if(outputs==null)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);return outputs.get(id).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));}
    @PostMapping("/tasks/{id}/cancel") public Map<String,Object> cancel(@PathVariable String id){return tasks.wire(owner(),tasks.cancel(owner(),id));}
    public record Instruction(String message){}
    @PostMapping("/tasks/{id}/instructions") public Map<String,Object> instruct(@PathVariable String id,@RequestBody Instruction instruction){return tasks.wire(owner(),tasks.instruct(owner(),id,instruction.message()));}
    @GetMapping("/tasks/{id}/plan") public Map<String,Object> plan(@PathVariable String id){return tasks.wirePlan(tasks.plan(owner(),id));}
    @GetMapping("/tasks/{id}/plan/revisions") public List<Map<String,Object>> revisions(@PathVariable String id){return tasks.planRevisions(owner(),id).stream().map(tasks::wirePlan).toList();}
    @DeleteMapping("/tasks/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id){tasks.delete(owner(),id);}
    @GetMapping("/state") public Map<String,Object> state(){return tasks.state(owner());}
    @GetMapping("/events") public CompanionTasks.Replay events(@RequestParam(defaultValue="0") long afterSequence){return tasks.replay(owner(),afterSequence);}
}
