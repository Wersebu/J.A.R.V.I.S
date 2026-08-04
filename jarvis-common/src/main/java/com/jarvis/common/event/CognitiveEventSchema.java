package com.jarvis.common.event;

import java.util.List;

/**
 * Description of one supported cognitive event type.
 *
 * @param event event name
 * @param description event description
 * @param metadataFields expected metadata fields
 */
public record CognitiveEventSchema(
        CognitiveEventType event,
        String description,
        List<String> metadataFields
) {
}
