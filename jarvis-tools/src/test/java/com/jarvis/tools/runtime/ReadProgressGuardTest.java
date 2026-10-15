package com.jarvis.tools.runtime;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
class ReadProgressGuardTest {
 @Test void varyingQueriesWithSameEvidenceWarnThenStop() {
  var guard = new ReadProgressGuard();
  guard.observe("pc", "READ", true, false, Map.of("content", "workMap=1-8; target=Blukan3r"));
  for (int i=0;i<4;i++) guard.observe("pc", "GREP", true, false, Map.of("content", "workMap=1-8; target=Blukan3r", "query", "query"+i));
  assertThat(guard.shouldWarn()).isTrue();
  assertThat(guard.shouldStop()).isFalse();
  for (int i=0;i<4;i++) guard.observe("pc", "READ", true, false, Map.of("content", "workMap=1-8; target=Blukan3r", "startLine", i));
  assertThat(guard.shouldStop()).isTrue();
 }
 @Test void newEvidenceAndEditsAllowLegitimateExploration() {
  var guard=new ReadProgressGuard();
  for(int i=0;i<20;i++) guard.observe("pc", "READ", true, false, Map.of("content", "new source code line "+i));
  assertThat(guard.shouldWarn()).isFalse();
  for(int i=0;i<8;i++) guard.observe("pc", "GREP", true, false, Map.of("matches", 0));
  assertThat(guard.shouldStop()).isTrue();
  guard.observe("pc", "EDIT", true, true, Map.of());
  assertThat(guard.shouldStop()).isFalse();
 }
}
