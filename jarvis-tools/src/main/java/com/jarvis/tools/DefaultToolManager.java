package com.jarvis.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

/**
 * Spring-backed registry for native J.A.R.V.I.S. tools.
 */
@Service
public class DefaultToolManager implements ToolManager {
    @Autowired(required=false)
    private com.jarvis.common.companion.ActivityTracker activityTracker;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.jarvis.tools.taskplan.DynamicPlanService companionPlans;

    @Autowired(required=false)
    private org.springframework.beans.factory.ObjectProvider<com.jarvis.common.companion.CompanionEventSink> companionEvents;

    private void agentEvent(String owner,String task,String type,ToolRequest request,Map<String,Object> details) {
        if(task==null||companionEvents==null)return;
        var sink=companionEvents.getIfAvailable();if(sink==null)return;
        var payload=new LinkedHashMap<String,Object>(details);payload.put("conversationId",request.conversationId());payload.put("requestId",request.requestId());payload.put("tool",request.toolName());payload.put("operation",request.operation());
        sink.record(owner,type,task,payload);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultToolManager.class);

    private final Map<String, JarvisTool> staticTools;
    private final List<DynamicToolSource> dynamicSources;

    /**
     * Creates the manager and registers all discovered tools.
     *
     * @param discoveredTools Spring-discovered tools
     */
    public DefaultToolManager(List<JarvisTool> discoveredTools) {
        this(discoveredTools, List.of());
    }

    /**
     * Creates the manager and registers all static and dynamic tool sources.
     *
     * @param discoveredTools Spring-discovered tools
     * @param dynamicSources runtime-discovered tool sources
     */
    @Autowired
    public DefaultToolManager(List<JarvisTool> discoveredTools, List<DynamicToolSource> dynamicSources) {
        Map<String, JarvisTool> registered = new LinkedHashMap<>();
        for (JarvisTool tool : discoveredTools) {
            JarvisTool previous = registered.putIfAbsent(normalize(tool.getName()), tool);
            if (previous != null) {
                throw new ToolException("Duplicate tool registered: " + tool.getName());
            }
            LOGGER.info("[TOOL] Registered tool name={}", tool.getName());
        }
        this.staticTools = Map.copyOf(registered);
        this.dynamicSources = dynamicSources == null ? List.of() : List.copyOf(dynamicSources);
    }

    /**
     * Model-facing native tool names are always lowercased (see {@code NativeToolSchemaMapper}),
     * so lookups must be case-insensitive regardless of how a tool's own {@code getName()} is cased.
     */
    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    @Override
    public List<JarvisTool> listTools() {
        return List.copyOf(combinedTools().values());
    }

    @Override
    public Optional<JarvisTool> findTool(String name) {
        return Optional.ofNullable(combinedTools().get(normalize(name)));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        if (request == null || request.toolName() == null || request.toolName().isBlank()) {
            throw new ToolException("Tool name is required");
        }
        if(!com.jarvis.common.companion.AgentScope.permits(request.toolName(),request.operation()))throw new ToolException("Capability is outside this agent's assigned scope");
        String companionTask=com.jarvis.common.companion.TaskExecutionContext.current();
        String companionOwner=companionTask==null?null:com.jarvis.common.auth.CurrentUserContext.requireAuthenticatedUserId();
        if(companionTask!=null&&companionPlans!=null)companionPlans.requireActive(companionOwner,companionTask);
        JarvisTool tool = combinedTools().get(normalize(request.toolName()));
        if (tool == null) {
            throw new ToolException("Tool not registered: " + request.toolName());
        }
        LOGGER.info("[TOOL] Executing tool={} operation={}", request.toolName(), request.operation());
        ToolResult result;
        long started=System.nanoTime();
        agentEvent(companionOwner,companionTask,"agent.tool.started",request,Map.of());
        try(var activity=activityTracker==null?null:activityTracker.start("TOOL",request.requestId(),request.toolName()+"__"+request.operation())) {
            result = tool.execute(request);
        } catch(RuntimeException failure) {
            agentEvent(companionOwner,companionTask,"agent.tool.failed",request,Map.of("durationMs",(System.nanoTime()-started)/1_000_000,"errorCode",failure.getClass().getSimpleName()));
            throw failure;
        }
        agentEvent(companionOwner,companionTask,result.success()?"agent.tool.completed":"agent.tool.failed",request,Map.of("durationMs",(System.nanoTime()-started)/1_000_000,"success",result.success(),"errorCode",result.errorCode()==null?"":result.errorCode()));
        if(companionTask!=null&&companionPlans!=null&&!Set.of("plan","taskplan").contains(normalize(request.toolName()))) {
            boolean success=result.success()&&!result.requiresApproval()&&!Boolean.parseBoolean(String.valueOf(result.data().get("running")))&&!Boolean.parseBoolean(String.valueOf(result.data().get("pending")));
            String status=String.valueOf(result.data().getOrDefault("status","")).toUpperCase(Locale.ROOT);if(Set.of("RUNNING","PENDING","QUEUED","IN_PROGRESS","FAILED","ERROR","CANCELLED").contains(status))success=false;
            Object exit=result.data().getOrDefault("exitCode",result.data().get("exit_code"));if(exit!=null){try{if(Long.parseLong(exit.toString())!=0)success=false;}catch(NumberFormatException e){success=false;}}
            try{var evidence=companionPlans.recordEvidence(companionOwner,companionTask,request.toolName(),request.operation(),success);var data=new LinkedHashMap<String,Object>(result.data());data.put("executionEvidenceRef",evidence.evidenceRef());result=new ToolResult(result.success(),result.tool(),result.operation(),result.requestId(),result.conversationId(),result.changed(),result.targetNodeIds(),result.message(),data,result.errorCode(),result.errorMessage(),result.requiresApproval(),result.draftId());}catch(IllegalArgumentException ignored){/* Cancellation won the race; do not fabricate completion evidence. */}
        }
        return new ToolResult(
                result.success(),
                result.tool().isBlank() ? request.toolName() : result.tool(),
                request.operation(),
                result.requestId().isBlank() ? request.requestId() : result.requestId(),
                result.conversationId().isBlank() ? request.conversationId() : result.conversationId(),
                result.changed(),
                result.targetNodeIds(),
                result.message(),
                result.data(),
                result.errorCode(),
                result.errorMessage(),
                result.requiresApproval(),
                result.draftId()
        );
    }

    private Map<String, JarvisTool> combinedTools() {
        Map<String, JarvisTool> combined = new LinkedHashMap<>(staticTools);
        for (DynamicToolSource source : dynamicSources) {
            for (JarvisTool tool : source.tools()) {
                String key = normalize(tool.getName());
                JarvisTool previous = combined.putIfAbsent(key, tool);
                if (previous != null) {
                    throw new ToolException("Duplicate dynamic tool registered: " + tool.getName());
                }
            }
        }
        return combined;
    }
}
