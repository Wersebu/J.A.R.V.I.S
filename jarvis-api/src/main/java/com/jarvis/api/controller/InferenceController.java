package com.jarvis.api.controller;
import com.jarvis.common.ai.*;
import com.jarvis.memory.auth.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@RestController
@RequestMapping("/api/v1/inference")
public class InferenceController {
 private final org.springframework.beans.factory.ObjectProvider<InferenceControl> provider;
 private InferenceControl control(){var value=provider.getIfAvailable();if(value==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"INFERENCE_UNAVAILABLE");return value;}
 public InferenceController(org.springframework.beans.factory.ObjectProvider<InferenceControl> provider){this.provider=provider;}
 private org.springframework.beans.factory.ObjectProvider<InferenceModelOperations> models;
 @org.springframework.beans.factory.annotation.Autowired public void modelOperations(org.springframework.beans.factory.ObjectProvider<InferenceModelOperations> value){models=value;}
 private InferenceModelOperations modelOperations(){var value=models.getIfAvailable();if(value==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"INFERENCE_UNAVAILABLE");return value;}
 public record ImportRequest(String path,String sha256){}
 @PostMapping("/downloads") public Object download(@RequestBody InferenceModelOperations.Download body,HttpServletRequest request){admin(request);return modelOperations().download(body);}
 @GetMapping("/operations") public Object operations(HttpServletRequest request){admin(request);return modelOperations().operations();}
 @PostMapping("/operations/{id}/cancel") public Object cancel(@PathVariable String id,HttpServletRequest request){admin(request);return modelOperations().cancel(id);}
 @PostMapping("/models/import") public Object importModel(@RequestBody ImportRequest body,HttpServletRequest request){admin(request);return modelOperations().importModel(body.path(),body.sha256());}
 @DeleteMapping("/models") public void delete(@RequestParam String engine,@RequestParam String model,HttpServletRequest request){admin(request);modelOperations().delete(engine,model);}
 private org.springframework.beans.factory.ObjectProvider<InferenceDiagnosticsControl> diagnostics;
 @org.springframework.beans.factory.annotation.Autowired public void diagnostics(org.springframework.beans.factory.ObjectProvider<InferenceDiagnosticsControl> value){diagnostics=value;}
 private InferenceDiagnosticsControl diagnostics(){var value=diagnostics.getIfAvailable();if(value==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"INFERENCE_UNAVAILABLE");return value;}
 @GetMapping("/logs") public Object logs(HttpServletRequest request){admin(request);return diagnostics().logs();}
 @GetMapping("/installation") public Object installation(HttpServletRequest request){admin(request);return diagnostics().installation();}
 @PostMapping("/installation") public Object install(HttpServletRequest request){admin(request);return diagnostics().install();}
 @PostMapping("/benchmark") public Object benchmark(HttpServletRequest request){admin(request);return diagnostics().benchmark();}
 public record SwitchRequest(String engine,String model,Map<String,Object> parameters){}
 public record ProcessRequest(String action){}
 private void admin(HttpServletRequest request){if(!(request.getAttribute("jarvis.user") instanceof UserAccount u)||!u.active()||!"ADMIN".equals(u.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"INFERENCE_ADMIN_REQUIRED");}
 @GetMapping("/state") public Map<String,Object> state(HttpServletRequest request){admin(request);return control().state();}
 @GetMapping("/configuration") public Object configuration(HttpServletRequest request){admin(request);return control().selection();}
 @PutMapping("/configuration") public Object configuration(@RequestBody SwitchRequest body,HttpServletRequest request){admin(request);return control().switchTo(body.engine(),body.model(),body.parameters());}
 @GetMapping("/resources") public Object resources(HttpServletRequest request){admin(request);return control().state().get("resources");}
 @GetMapping("/engines") public Object engines(HttpServletRequest request){admin(request);return control().state().get("engines");}
 @GetMapping("/models") public Object models(@RequestParam String engine,HttpServletRequest request){admin(request);return control().models(engine);}
 @PostMapping("/selection") public Map<String,Object> select(@RequestBody SwitchRequest body,HttpServletRequest request){admin(request);return control().switchTo(body.engine(),body.model(),body.parameters());}
 @PostMapping("/process") public Map<String,Object> process(@RequestBody ProcessRequest body,HttpServletRequest request){admin(request);return control().process(body.action());}
 @ExceptionHandler(AIProviderException.class) public org.springframework.http.ResponseEntity<Map<String,String>> failure(AIProviderException e){return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code",e.getMessage().split(":",2)[0],"message",e.getMessage()));}
}
