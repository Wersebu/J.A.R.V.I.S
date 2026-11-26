package com.jarvis.api.companion;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.memory.companion.SQLiteCompanionDocumentStore;
import com.jarvis.tools.runtime.ToolOutputStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ToolOutputPersistenceTest {
    @TempDir Path temp;
    @Test void archivedEvidenceSurvivesRestartAndCannotCrossAccounts(){
        String[] id={null};Path file=temp.resolve("outputs.db");String content="Pełny materiał. ".repeat(10000);
        try(var documents=new SQLiteCompanionDocumentStore(true,file.toString())){
            var outputs=new ToolOutputStore();outputs.configurePersistence(documents);CurrentUserContext.runAs("alice",()->id[0]=outputs.put(content));
            CurrentUserContext.runAs("bob",()->assertTrue(outputs.get(id[0]).isEmpty()));
        }
        try(var documents=new SQLiteCompanionDocumentStore(true,file.toString())){
            var outputs=new ToolOutputStore();outputs.configurePersistence(documents);
            CurrentUserContext.runAs("alice",()->assertEquals(content,outputs.get(id[0]).orElseThrow()));
            CurrentUserContext.runAs("bob",()->assertTrue(outputs.get(id[0]).isEmpty()));
        }
    }
}
