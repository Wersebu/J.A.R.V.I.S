package com.jarvis.api.service.moderation;

import com.jarvis.api.dto.moderation.ModerationHealthResponse;
import com.jarvis.api.dto.moderation.ModerationRequest;
import com.jarvis.api.dto.moderation.ModerationResult;

/**
 * Stateless service-to-service moderation boundary for TopkiMC.
 */
public interface TopkiMcModerationService {

    /**
     * Moderates one TopkiMC server profile payload.
     *
     * @param request untrusted moderation payload
     * @param requestId correlation id
     * @return strict moderation result
     */
    ModerationResult moderate(ModerationRequest request, String requestId, String keyId);

    /**
     * Returns safe operational health for the moderation feature.
     *
     * @return health response
     */
    ModerationHealthResponse health();
}
