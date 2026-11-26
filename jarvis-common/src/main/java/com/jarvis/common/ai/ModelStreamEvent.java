package com.jarvis.common.ai;

/** Provider-neutral deltas. Tool arguments/reasoning are internal data, never UI answer text. */
public record ModelStreamEvent(Type type,String text,String itemId,String name) {
    public enum Type { FIRST_TOKEN, CONTENT, TOOL_ARGUMENTS_DELTA, TOOL_CALL_COMPLETE }
    public static ModelStreamEvent content(String text){return new ModelStreamEvent(Type.CONTENT,text,"","");}
}
