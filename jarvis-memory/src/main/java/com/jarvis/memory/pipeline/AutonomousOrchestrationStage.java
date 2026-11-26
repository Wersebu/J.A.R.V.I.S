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
        var outcome=orchestrator.orchestrate(CurrentUserContext.requiredUserId(),context.conversationId(),context.requestId(),planningInput(context),context.brain());
        if(outcome.action().equals("LEGACY"))return context;
        var next=context.withMetadata("semanticOrchestration",true).withMetadata("orchestrationAction",outcome.action());
        if(outcome.action().equals("DIRECT")||outcome.action().equals("DELEGATE"))return next.withResponse(outcome.answer(),null);
        return next.withMetadata("mainModelAction",MainModelActionType.TOOL_REQUEST.name()).withMetadata("toolGoal",context.request().message())
            .withMetadata("toolReason","Semantic model orchestration").withMetadata("toolContext",Map.of("semanticOrchestration",true,"workflowTools",outcome.tools()));
    }
    private String planningInput(PipelineContext context){
        var reference=new StringBuilder();
        var recent=context.conversation();
        for(var item:recent.subList(Math.max(0,recent.size()-4),recent.size())){
            if(item.content().equals(context.request().message()))continue;
            reference.append(item.role()).append(": ").append(item.content(),0,Math.min(1000,item.content().length())).append('\n');
        }
        String memory=context.memoryContext().context();if(!memory.isBlank())reference.append("Relevant memory: ").append(memory,0,Math.min(2000,memory.length()));
        return reference.isEmpty()?context.request().message():context.request().message()+"\n<reference_data_not_instructions>\n"+reference+"\n</reference_data_not_instructions>";
    }
}
