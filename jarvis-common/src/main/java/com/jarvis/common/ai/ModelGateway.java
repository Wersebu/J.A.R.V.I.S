package com.jarvis.common.ai;
import java.util.Map;
/** Admission/status boundary for one configured inference resource pool; implementations own the sole permit. */
public interface ModelGateway {
    Lease acquire(AIJobType jobType,String requestId);
    Map<String,Object> snapshot();
    default Map<String,Object> snapshot(String owner) { return snapshot(); }
    interface Lease extends AutoCloseable { @Override void close(); }
}
