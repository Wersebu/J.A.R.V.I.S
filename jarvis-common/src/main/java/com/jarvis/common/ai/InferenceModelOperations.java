package com.jarvis.common.ai;
import java.util.*;
public interface InferenceModelOperations {
 record Download(String engine,String repository,String revision,String file,String sha256){}
 Map<String,Object> download(Download request);
 List<Map<String,Object>> operations();
 Map<String,Object> cancel(String operationId);
 Map<String,Object> importModel(String path,String sha256);
 void delete(String engine,String model);
}
