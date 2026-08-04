package com.jarvis.brain.decision;

import com.jarvis.common.dto.ChatRequest;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Deterministic task analyzer based on modular keyword strategies.
 */
@Service
public class RuleBasedTaskAnalyzer implements TaskAnalyzer {

    /**
     * Analyzes task type without invoking any model.
     *
     * @param request chat request
     * @return task analysis
     */
    @Override
    public TaskAnalysis analyze(ChatRequest request) {
        String query = normalize(request.message());
        if (query.isBlank()) {
            return new TaskAnalysis(TaskType.UNKNOWN, 0.20, "Empty request");
        }
        if (matches(query, "review", "code review", "przeglad kodu", "sprawdz kod")) {
            return new TaskAnalysis(TaskType.CODE_REVIEW, 0.91, "Code review request");
        }
        if (matches(query, "explain", "what is", "how does", "wyjasnij", "co to", "jak dziala")) {
            if (matches(query, "spring", "jarvis", "knowledge", "document", "file", "project")) {
                return new TaskAnalysis(TaskType.KNOWLEDGE_QA, 0.86, "Knowledge question");
            }
            return new TaskAnalysis(TaskType.SIMPLE_QA, 0.82, "Simple question");
        }
        if (matches(query, "java", "spring", "maven", "plugin", "program", "code", "class", "method", "bug", "compile")) {
            return new TaskAnalysis(TaskType.PROGRAMMING, 0.88, "Programming task");
        }
        if (matches(query, "summarize", "summary", "podsumuj", "streszcz")) {
            return new TaskAnalysis(TaskType.SUMMARIZATION, 0.89, "Summarization request");
        }
        if (matches(query, "translate", "tlumacz", "przetlumacz")) {
            return new TaskAnalysis(TaskType.TRANSLATION, 0.88, "Translation request");
        }
        if (matches(query, "analyze", "analyse", "analiza", "przeanalizuj", "compare", "porownaj")) {
            return new TaskAnalysis(TaskType.ANALYSIS, 0.87, "Analysis detected");
        }
        if (matches(query, "write", "generate", "create", "stworz", "napisz", "wygeneruj")) {
            if (matches(query, "exam", "exams", "test", "tests", "article", "plan", "document", "five", "5")) {
                return new TaskAnalysis(TaskType.CONTENT_GENERATION, 0.90, "Large content generation request");
            }
            return new TaskAnalysis(TaskType.CREATIVE_WRITING, 0.82, "Creative writing request");
        }
        if (isGreeting(query)) {
            return new TaskAnalysis(TaskType.CONVERSATION, 0.94, "Short conversation");
        }
        return new TaskAnalysis(TaskType.CONVERSATION, 0.62, "Default conversation");
    }

    private boolean matches(String query, String... keywords) {
        for (String keyword : keywords) {
            if (query.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGreeting(String query) {
        return query.matches("^(hello|hi|hey|czesc|cześć|siema|witaj)[!.?\\s]*$");
    }

    private String normalize(String message) {
        return message == null ? "" : message.strip().toLowerCase(Locale.ROOT);
    }
}
