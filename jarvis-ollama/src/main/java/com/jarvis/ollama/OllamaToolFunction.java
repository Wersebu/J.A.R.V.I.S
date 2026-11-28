package com.jarvis.ollama;

import java.util.Map;

/**
 * Ollama function call payload.
 *
 * @param name function name
 * @param arguments structured function arguments
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record OllamaToolFunction(
        String name,
        Map<String, Object> arguments,
        Integer index
) {
    public OllamaToolFunction(String name,Map<String,Object> arguments){this(name,arguments,null);}
}
