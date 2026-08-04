package com.jarvis.core.pipeline;

import com.jarvis.common.dto.ChatResponse;
import com.jarvis.memory.pipeline.PipelineContext;
import org.springframework.stereotype.Service;

/**
 * Maps final pipeline state into external API responses.
 */
@Service
public class ResponseMapper {

    /**
     * Maps a pipeline context to a chat response.
     *
     * @param context final pipeline context
     * @return chat response
     */
    public ChatResponse toChatResponse(PipelineContext context) {
        String response = context.response() == null ? "" : context.response();
        return new ChatResponse(response);
    }
}
