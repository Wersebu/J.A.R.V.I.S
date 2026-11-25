package com.jarvis.api.companion;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.brain.BrainRouter;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
import java.util.function.Consumer;

/** Ground-truth announcements need no inference and cannot invent progress. */
@Service
public class TaskAnnouncements {
    private final boolean enabled;
    public TaskAnnouncements(List<AIProvider> providers,BrainRouter router,
        @Value("${jarvis.companion.announcements.enabled:true}") boolean enabled,
        @Value("${jarvis.companion.announcements.personality:Concise}") String personality){this.enabled=enabled;}
    public void generate(String owner,CompanionTasks.Task task,String kind,Consumer<String> sink){
        if(!enabled)return;
        String text=switch(kind){
            case "ACKNOWLEDGMENT"->task.state().equals("QUEUED")?"Przyjęto zadanie do wykonania w tle.":null;
            case "PROGRESS"->task.state().equals("RUNNING")?"Rozpoczęto wykonywanie zadania.":null;
            case "COMPLETED"->task.state().equals("COMPLETED")?"Zadanie zakończone. Wynik jest dostępny w szczegółach zadania.":null;
            case "FAILED"->task.state().equals("FAILED")?"Zadanie nie powiodło się. Szczegóły są dostępne w historii.":null;
            default->null;
        };
        if(text!=null)sink.accept(text);
    }
    public void close(){}
}
