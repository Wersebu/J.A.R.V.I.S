package com.jarvis.common.integration;
import java.util.*;
/** Provider-owned tokens remain inside their adapter. New integrations implement this seam. */
public interface IntegrationStatusSource {
 Set<String> providers();
 IntegrationStatus status(String owner,String provider,String operation);
}
