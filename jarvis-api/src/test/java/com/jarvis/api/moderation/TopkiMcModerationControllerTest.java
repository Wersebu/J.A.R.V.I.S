package com.jarvis.api.moderation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.auth.TopkiMcModerationAuthFilter;
import com.jarvis.api.auth.TopkiMcModerationAuthProperties;
import com.jarvis.api.controller.TopkiMcModerationController;
import com.jarvis.api.dto.moderation.ModerationDecision;
import com.jarvis.api.dto.moderation.ModerationHealthResponse;
import com.jarvis.api.dto.moderation.ModerationRequest;
import com.jarvis.api.dto.moderation.ModerationResult;
import com.jarvis.api.dto.moderation.ModerationRisk;
import com.jarvis.api.service.moderation.TopkiMcModerationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TopkiMcModerationControllerTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @Test
    void acceptsContractRequestWithServiceBearerToken() throws Exception {
        CapturingService service = new CapturingService();
        MockMvc mvc = mvc(service, KEY);

        mvc.perform(post("/v1/moderate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + KEY)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("CLEAN"))
                .andExpect(jsonPath("$.risk").value("LOW"))
                .andExpect(jsonPath("$.reasonCode").value("NO_VIOLATIONS"));

        assertThat(service.request.get()).isNotNull();
        assertThat(service.keyId.get()).isNotBlank();
    }

    @Test
    void rejectsMissingOrWrongBearerWithoutCallingService() throws Exception {
        CapturingService service = new CapturingService();
        MockMvc mvc = mvc(service, KEY);

        mvc.perform(post("/v1/moderate").contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/moderate").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer wrong")
                        .content(validRequest()))
                .andExpect(status().isUnauthorized());

        assertThat(service.request.get()).isNull();
    }

    @Test
    void failsClosedWhenKeyIsNotConfigured() throws Exception {
        MockMvc mvc = mvc(new CapturingService(), "");

        mvc.perform(post("/v1/moderate").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + KEY)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUnknownRequestFields() throws Exception {
        MockMvc mvc = mvc(new CapturingService(), KEY);
        String body = validRequest().replace("\"policyVersion\":\"v1\"", "\"policyVersion\":\"v1\",\"extra\":\"nope\"");

        mvc.perform(post("/v1/moderate").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + KEY)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void healthIsAlsoAuthenticatedAndSafe() throws Exception {
        MockMvc mvc = mvc(new CapturingService(), KEY);

        mvc.perform(get("/v1/moderate/health")
                        .header("Authorization", "Bearer " + KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.policyVersion").value("v1"));
    }

    private MockMvc mvc(TopkiMcModerationService service, String key) {
        TopkiMcModerationAuthProperties properties = new TopkiMcModerationAuthProperties();
        properties.setApiKey(key);
        return MockMvcBuilders.standaloneSetup(new TopkiMcModerationController(service, new ObjectMapper()))
                .addFilters(new TopkiMcModerationAuthFilter(properties))
                .build();
    }

    private String validRequest() {
        return """
                {
                  "serverId":"server-1",
                  "ownerIdHash":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "category":"SURVIVAL",
                  "languageHint":"pl",
                  "title":"Najlepszy serwer",
                  "plainText":"Zapraszamy na spokojny serwer survival.",
                  "externalUrls":["https://discord.gg/example"],
                  "imageUrls":[],
                  "youtubeVideoIds":[],
                  "technicalCheckSummary":{"length":42,"tagCount":0,"maxDepth":0,"heuristicRiskSignals":[]},
                  "policyVersion":"v1"
                }
                """;
    }

    private static final class CapturingService implements TopkiMcModerationService {
        private final AtomicReference<ModerationRequest> request = new AtomicReference<>();
        private final AtomicReference<String> keyId = new AtomicReference<>();

        @Override
        public ModerationResult moderate(ModerationRequest request, String requestId, String keyId) {
            this.request.set(request);
            this.keyId.set(keyId);
            return new ModerationResult(ModerationDecision.CLEAN, ModerationRisk.LOW, List.of(),
                    "NO_VIOLATIONS", "Nie wykryto naruszen", false, "test-model", "v1");
        }

        @Override
        public ModerationHealthResponse health() {
            return new ModerationHealthResponse(true, true, true, true, "v1");
        }
    }
}
