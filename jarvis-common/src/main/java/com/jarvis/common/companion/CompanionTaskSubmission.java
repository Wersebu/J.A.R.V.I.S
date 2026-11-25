package com.jarvis.common.companion;

import java.util.Map;

/** API-independent task boundary. Owner is supplied by authenticated server context, never model arguments. */
public interface CompanionTaskSubmission {
    Map<String,Object> submit(String owner,String conversationId,String message,String role,String idempotencyKey);
    Map<String,Object> get(String owner,String taskId);
    Map<String,Object> cancel(String owner,String taskId);
    Map<String,Object> instruct(String owner,String taskId,String message);
}
