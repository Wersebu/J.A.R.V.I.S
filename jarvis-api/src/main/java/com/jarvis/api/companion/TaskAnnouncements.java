package com.jarvis.api.companion;
import com.jarvis.common.ai.*;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.brain.BrainRouter;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Tool-free, bounded, optional personality generation. Never runs on the request/task-state lock. */
@Service
public class TaskAnnouncements {
    private volatile String cachedAcknowledgment;
    private final List<AIProvider> providers;private final BrainRouter router;private final boolean enabled;private final String personality;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->{Thread t=new Thread(r,"task-announcements");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public TaskAnnouncements(List<AIProvider> providers,BrainRouter router,
        @Value("${jarvis.companion.announcements.enabled:false}") boolean enabled,
        @Value("${jarvis.companion.announcements.personality:Helpful, concise and calm.}") String personality){this.providers=List.copyOf(providers);this.router=router;this.enabled=enabled;this.personality=personality;}
    public void generate(String owner,CompanionTasks.Task task,String kind,Consumer<String> sink) {
        if(!enabled)return;
        if(kind.equals("ACKNOWLEDGMENT") && cachedAcknowledgment!=null){sink.accept(cachedAcknowledgment);return;}
        try{worker.execute(()->CurrentUserContext.runAs(owner,()->InferencePriorityContext.runBackground(()->{
            try {
                Brain brain=router.select(new ChatRequest("announcement-"+task.taskId(),"Brief task status announcement"));
                AIProvider provider=providers.stream().filter(p->p.provider().equalsIgnoreCase(brain.provider())).findFirst().orElseThrow();
                String prompt="Generate one short user-facing task announcement, no tools, no reasoning. Personality: "+personality
                    +"\nVerified task state: "+task.state()+". Announcement kind: "+kind
                    +". Never imply completion unless state is COMPLETED. Do not invent task progress, results, percentages or facts. Return only the spoken text.";
                String text=provider.chat(brain,prompt,AIJobType.BACKGROUND).response().strip();
                if(!text.isEmpty() && text.length()<=2000){if(kind.equals("ACKNOWLEDGMENT"))cachedAcknowledgment=text;sink.accept(text);}
            }catch(RuntimeException ignored){/* Optional generation must not change task outcome. */}
        })));}catch(RejectedExecutionException ignored){/* Bounded optional queue. */}
    }
    @PreDestroy public void close(){worker.shutdownNow();}
}
