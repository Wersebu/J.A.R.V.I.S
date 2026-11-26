package com.jarvis.common.companion;
/** Lazy boundary for background components publishing into the existing task journal. */
public interface CompanionEventSink {void record(String owner,String type,String taskId,Object payload);}
