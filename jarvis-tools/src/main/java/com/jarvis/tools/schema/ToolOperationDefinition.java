package com.jarvis.tools.schema;

import java.util.List;

/**
 * Model-facing tool operation definition.
 *
 * @param name operation name
 * @param description operation description
 * @param arguments argument definitions
 * @param write whether the operation changes state
 * @param safetyLevel safety level
 */
public record ToolOperationDefinition(
        String name,
        String description,
        List<ToolArgumentDefinition> arguments,
        boolean write,
        ToolSafetyLevel safetyLevel,
        String purpose,
        List<String> prerequisites
) {

    public ToolOperationDefinition(String name,String description,List<ToolArgumentDefinition> arguments,boolean write,ToolSafetyLevel safetyLevel){this(name,description,arguments,write,safetyLevel,"GENERAL",List.of());}
    public ToolOperationDefinition setup(){return new ToolOperationDefinition(name,description,arguments,write,safetyLevel,"INTEGRATION_SETUP",prerequisites);}
    public ToolOperationDefinition requires(String... tools){return new ToolOperationDefinition(name,description,arguments,write,safetyLevel,purpose,List.of(tools));}
    /**
     * Creates an immutable operation definition.
     */
    public ToolOperationDefinition {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        purpose=purpose==null?"GENERAL":purpose;prerequisites=prerequisites==null?List.of():List.copyOf(prerequisites);
    }
}
