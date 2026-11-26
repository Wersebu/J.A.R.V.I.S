package com.jarvis.tools.runtime;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps full tool outputs that were too large for the model's context (or were later elided from
 * the conversation history), so the model can page through them with the {@code output} tool
 * instead of silently working from a truncated view.
 */
@Service
public class ToolOutputStore {
    private com.jarvis.common.companion.CompanionDocumentStore persistent;
    @org.springframework.beans.factory.annotation.Autowired
    public synchronized void configurePersistence(com.jarvis.common.companion.CompanionDocumentStore store){this.persistent=store;}

    private static final int MAX_ENTRIES = 300;

    private final Map<String, String> outputs = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /**
     * Stores an output.
     *
     * @param text full text
     * @return id to pass to {@code output__read}
     */
    public synchronized String put(String text) {
        String id = "out-" + UUID.randomUUID();
        String owner=com.jarvis.common.auth.CurrentUserContext.requiredUserId();
        if(persistent!=null)persistent.put(new com.jarvis.common.companion.CompanionDocumentStore.Document(owner,"tool-output",id,text==null?"":text,java.time.Instant.now()));
        outputs.put(owner+"/"+id, text == null ? "" : text);
        return id;
    }

    /**
     * Returns a stored output.
     *
     * @param id output id
     * @return text, when still stored
     */
    public synchronized Optional<String> get(String id) {
        String owner=com.jarvis.common.auth.CurrentUserContext.requiredUserId();String key=id==null?"":id.strip();
        if(persistent!=null)return persistent.find(owner,"tool-output",key).map(com.jarvis.common.companion.CompanionDocumentStore.Document::body);
        return Optional.ofNullable(outputs.get(owner+"/"+key));
    }
}
