package com.jarvis.api.error;
import com.jarvis.common.ai.AIProviderException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ApiExceptionHandlerTest {
 @Test void contextOverflowIsNotReportedAsProviderOutageOrExposedPrompt(){var result=new ApiExceptionHandler().handleAiProviderException(new AIProviderException("CONTEXT_BUDGET_EXCEEDED: private prompt fragment"));assertThat(result.getStatusCode().value()).isEqualTo(422);assertThat(result.getBody().message()).startsWith("CONTEXT_BUDGET_EXCEEDED:").doesNotContain("private prompt");}
 @Test void genuineProviderFailureKeepsExistingSafeResponse(){var result=new ApiExceptionHandler().handleAiProviderException(new AIProviderException("Connection refused: private endpoint"));assertThat(result.getStatusCode().value()).isEqualTo(503);assertThat(result.getBody().message()).isEqualTo("AI provider is unavailable");}
}
