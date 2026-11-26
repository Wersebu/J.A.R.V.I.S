package com.jarvis.memory.pipeline;
import com.jarvis.common.companion.*;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.memory.conversation.ConversationTitleService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service @Order(52)
public class AutonomousOrchestrationStage implements PipelineStage {
    private final ObjectProvider<AutonomousOrchestration> orchestrators;
    private final ConversationTitleService titles;
    public AutonomousOrchestrationStage(ObjectProvider<AutonomousOrchestration> orchestrators,ConversationTitleService titles){this.orchestrators=orchestrators;this.titles=titles;}
    public String name(){return "AutonomousOrchestrationStage";}
    public PipelineContext execute(PipelineContext context){
        if(TaskExecutionContext.current()!=null)return context;
        titles.maybeGenerateTitleAsync(context.conversationId(),context.brain());
        // Attachment/workspace execution retains its existing context-bearing pipeline.
        if(!context.request().attachments().isEmpty()||!context.images().isEmpty()||!context.request().activeCodingWorkspaceId().isBlank()||!context.request().workingDirectory().isBlank())return context;
        var orchestrator=orchestrators.getIfAvailable();if(orchestrator==null)return context;
        var outcome=orchestrator.orchestrate(CurrentUserContext.requiredUserId(),context.conversationId(),context.requestId(),planningContext(context),context.brain());
        if(outcome.action().equals("LEGACY"))return context;
        var next=context.withMetadata("orchestrationReferences",planningContext(context)).withMetadata("semanticOrchestration",true).withMetadata("orchestrationAction",outcome.action());
        if(outcome.action().equals("DIRECT")||outcome.action().equals("DELEGATE"))return next.withResponse(outcome.answer(),null);
        return next.withMetadata("mainModelAction",MainModelActionType.TOOL_REQUEST.name()).withMetadata("toolGoal",context.request().message())
            .withMetadata("toolReason","Semantic model orchestration").withMetadata("toolContext",Map.of("semanticOrchestration",true,"executionMode",outcome.action(),"workflowTools",outcome.tools()));
    }
    private AutonomousOrchestration.Context planningContext(PipelineContext context){
        var history=new java.util.ArrayList<AutonomousOrchestration.Reference>();var results=new java.util.ArrayList<AutonomousOrchestration.Reference>();
        var recent=context.conversation();
        for(var message:recent.subList(Math.max(0,recent.size()-4),recent.size())){
            if(message.requestId().equals(context.requestId())||message.status()!=com.jarvis.common.memory.ConversationMessageStatus.FINAL)continue;
            var reference=new AutonomousOrchestration.Reference("conversation",message.role().name(),message.requestId(),message.content().substring(0,Math.min(400,message.content().length())));
            if(message.requestId().startsWith("task-result/")||message.requestId().startsWith("task-group/"))results.add(reference);else history.add(reference);
        }
        String memory=context.memoryContext().context();var memories=memory.isBlank()?java.util.List.<AutonomousOrchestration.Reference>of():java.util.List.of(new AutonomousOrchestration.Reference("memory","REFERENCE","",memory.substring(0,Math.min(600,memory.length()))));
        return new AutonomousOrchestration.Context(context.request().message(),history,memories,results,java.util.List.of());
    }
}
