package com.jarvis.common.integration;
import java.time.Instant;
import java.util.*;
/** Safe metadata only. Never contains access/refresh tokens or private provider content. */
public record IntegrationStatus(String provider,String operation,IntegrationState state,boolean connected,boolean scopesKnown,Set<String> grantedScopes,Set<String> requiredScopes,Set<String> missingScopes,String code,Instant observedAt) {
 public IntegrationStatus {grantedScopes=Set.copyOf(grantedScopes);requiredScopes=Set.copyOf(requiredScopes);missingScopes=Set.copyOf(missingScopes);}
 public boolean needsAuthorization(){return Set.of(IntegrationState.DISCONNECTED,IntegrationState.AUTHORIZATION_REQUIRED,IntegrationState.MISSING_SCOPE,IntegrationState.TOKEN_EXPIRED).contains(state);}
}
