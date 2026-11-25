package com.jarvis.api.companion;
import org.springframework.context.annotation.*;
import com.jarvis.common.companion.ActivityTracker;
@Configuration
public class CompanionActivityConfiguration {
    @Bean public ActivityTracker activityTracker(){return new ActivityTracker();}
}
