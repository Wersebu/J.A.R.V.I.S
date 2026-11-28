package com.jarvis.core.google;
/** Typed, sanitized provider failure. The code controls recovery; prose never triggers OAuth. */
public class GoogleException extends RuntimeException {
 private final String code;
 public GoogleException(String message){this("ERROR",message);}
 public GoogleException(String code,String message){super(message);this.code=code;}
 public String code(){return code;}
 public boolean authorizationRequired(){return java.util.Set.of("DISCONNECTED","AUTHORIZATION_REQUIRED","TOKEN_EXPIRED","MISSING_SCOPE").contains(code);}
}
