package com.jarvis.tools.taskplan;
import java.time.Instant;
import java.util.List;
public record DynamicPlan(String planId,String taskId,long revision,String title,Instant createdAt,Instant updatedAt,String status,List<Step> steps,int completedStepCount,int totalStepCount) {
    public DynamicPlan {steps=List.copyOf(steps);}
    public record Step(String stepId,String title,String description,int position,String status,Instant startedAt,Instant completedAt,String resultSummary,String error,String evidenceRef) {}
}
