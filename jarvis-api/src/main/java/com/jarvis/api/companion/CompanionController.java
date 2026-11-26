package com.jarvis.api.companion;
import com.jarvis.common.auth.CurrentUserContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;
@RestController
@RequestMapping("/api/v1/companion")
public class CompanionController {
    private final CompanionTasks tasks;
    @org.springframework.beans.factory.annotation.Value("${jarvis.companion.provider-mode:LIVE}") private String providerMode="LIVE";
    public CompanionController(CompanionTasks tasks){this.tasks=tasks;}
    private String owner(){return CurrentUserContext.requireAuthenticatedUserId();}
    @GetMapping("/capabilities") public Map<String,Object> capabilities(){var result=new LinkedHashMap<String,Object>(Map.of("contractVersion",CompanionTasks.version(),"tasks",true,"voiceStreaming",false,"durableHistory",tasks.durableHistory(),"eventRetention",512,"maxActiveTasks",128,"providerMode",providerMode,"taskPlans",true,"planEvidenceRequired",true));result.put("inferenceTelemetry",true);result.put("asyncDelegation",true);result.put("taskInstructions",true);result.put("interactiveChat",Map.of("requestIds",true,"multiplexing",true,"sameConversationPolicy","FIFO","requestControls",true,"requestReplay",true,"protocolRevision","1.6.0"));return result;}
    @PostMapping("/tasks") @ResponseStatus(HttpStatus.ACCEPTED) public CompanionTasks.Task submit(@RequestBody CompanionTasks.Request request){return tasks.submit(owner(),request);}
    @GetMapping("/tasks") public List<CompanionTasks.Task> list(){return tasks.list(owner());}
    @GetMapping("/tasks/{id}") public CompanionTasks.Task get(@PathVariable String id){return tasks.get(owner(),id);}
    @PostMapping("/tasks/{id}/cancel") public CompanionTasks.Task cancel(@PathVariable String id){return tasks.cancel(owner(),id);}
    public record Instruction(String message){}
    @PostMapping("/tasks/{id}/instructions") public CompanionTasks.Task instruct(@PathVariable String id,@RequestBody Instruction instruction){return tasks.instruct(owner(),id,instruction.message());}
    @GetMapping("/tasks/{id}/plan") public com.jarvis.tools.taskplan.DynamicPlan plan(@PathVariable String id){return tasks.plan(owner(),id);}
    @GetMapping("/tasks/{id}/plan/revisions") public List<com.jarvis.tools.taskplan.DynamicPlan> revisions(@PathVariable String id){return tasks.planRevisions(owner(),id);}
    @DeleteMapping("/tasks/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id){tasks.delete(owner(),id);}
    @GetMapping("/state") public Map<String,Object> state(){return tasks.state(owner());}
    @GetMapping("/events") public CompanionTasks.Replay events(@RequestParam(defaultValue="0") long afterSequence){return tasks.replay(owner(),afterSequence);}
}
