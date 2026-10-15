package com.jarvis.common.ai;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
class RequestTokenUsageTest {
 @Test void sumsPhysicalInferencesAndDoesNotInventMissingCounts() {
  var usage=new RequestTokenUsage();
  var first=usage.record(Map.of("inputTokens",120L,"outputTokens",18L));
  assertThat(first).containsEntry("totalInputTokens",120L).containsEntry("calls",1L);
  var next=usage.record(Map.of("inputTokens",200L,"outputTokens",0L));
  assertThat(next).containsEntry("totalInputTokens",320L).containsEntry("totalOutputTokens",18L);
  var missing=usage.record(Map.of("inputTokens",100L));
  assertThat(missing).doesNotContainKey("totalOutputTokens").containsEntry("totalInputTokens",420L);
  assertThat(usage.record(Map.of("outputTokens",2L))).doesNotContainKeys("totalInputTokens","totalOutputTokens");
  assertThat(new RequestTokenUsage().record(Map.of("inputTokens",0L,"outputTokens",0L)))
    .containsEntry("totalInputTokens",0L).containsEntry("totalOutputTokens",0L);
 }
}
