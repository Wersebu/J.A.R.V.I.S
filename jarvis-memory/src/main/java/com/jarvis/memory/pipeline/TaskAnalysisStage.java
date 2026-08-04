package com.jarvis.memory.pipeline;

import com.jarvis.brain.decision.TaskAnalyzer;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Determines the likely task type.
 */
@Service
@Order(20)
public class TaskAnalysisStage implements PipelineStage {

    private final TaskAnalyzer taskAnalyzer;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the task analysis stage.
     *
     * @param taskAnalyzer task analyzer
     * @param cognitiveEventBus event bus
     */
    public TaskAnalysisStage(TaskAnalyzer taskAnalyzer, CognitiveEventBus cognitiveEventBus) {
        this.taskAnalyzer = taskAnalyzer;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public String name() {
        return "TaskAnalysisStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        var analysis = taskAnalyzer.analyze(context.request());
        cognitiveEventBus.publish(CognitiveEventType.TASK_ANALYZED, "ANALYZED", "Task analyzed", null, Map.of(
                "taskType", analysis.taskType().name(),
                "confidence", analysis.confidence(),
                "reason", analysis.reason()
        ));
        return context.withTaskAnalysis(analysis);
    }
}
