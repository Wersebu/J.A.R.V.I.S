package com.jarvis.common.ai;
import java.util.Map;
/** Admission/status boundary for one configured inference resource pool; implementations own the sole permit. */
public interface ModelGateway {
    Lease acquire(AIJobType jobType,String requestId);
    /** Only callers that force num_gpu=0 may use this auxiliary CPU lease. */
    default Lease acquireCpuAuxiliary(AIJobType jobType,String requestId){return acquire(jobType,requestId);}
    Map<String,Object> snapshot();
    default Map<String,Object> snapshot(String owner) { return snapshot(); }
    interface Lease extends AutoCloseable { @Override void close(); }
}
