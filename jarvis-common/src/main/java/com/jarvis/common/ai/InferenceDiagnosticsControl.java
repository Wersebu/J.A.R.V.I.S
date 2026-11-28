package com.jarvis.common.ai;
import java.util.*;
public interface InferenceDiagnosticsControl {
 List<String> logs();
 Map<String,Object> benchmark();
 Map<String,Object> installation();
 Map<String,Object> install();
}
