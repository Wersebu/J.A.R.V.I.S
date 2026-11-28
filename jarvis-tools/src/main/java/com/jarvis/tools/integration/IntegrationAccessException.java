package com.jarvis.tools.integration;
import java.util.Map;
public final class IntegrationAccessException extends RuntimeException {
 private final String code;private final Map<String,Object> details;
 public IntegrationAccessException(String code,String message,Map<String,Object> details){super(message);this.code=code;this.details=Map.copyOf(details);}
 public String code(){return code;}public Map<String,Object> details(){return details;}
}
