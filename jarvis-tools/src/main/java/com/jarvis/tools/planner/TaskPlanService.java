package com.jarvis.tools.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-conversation agent task plans: the model writes down its steps once, then works through them
 * marking progress, instead of asking the user what to do next after every action.
 *
 * <p>Plans are kept in memory and mirrored to small JSON files (when a storage directory is
 * configured) so an unfinished plan survives a Core restart and a later "kontynuuj" turn can pick it
 * up again.</p>
 */
@Service
public class TaskPlanService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskPlanService.class);
    static final int MAX_STEPS = 40;
    static final int MAX_TEXT = 400;

    private final ObjectMapper objectMapper;
    private final Path storageDir;
    private final Map<String, TaskPlan> plans = new ConcurrentHashMap<>();

    /**
     * Creates the service.
     *
     * @param objectMapper JSON mapper
     * @param storageDir directory for persisted plans; blank keeps plans in memory only
     */
    public TaskPlanService(ObjectMapper objectMapper, @Value("${jarvis.planner.storage-dir:./data/plans}") String storageDir) {
        this.objectMapper = objectMapper.copy();
        this.storageDir = storageDir == null || storageDir.isBlank() ? null : Path.of(storageDir);
    }

    /**
     * Creates or replaces the plan of a conversation. The first step starts in progress.
     *
     * @param conversationId conversation id
     * @param goal overall goal
     * @param stepTitles ordered step titles
     * @return new plan
     */
    public TaskPlan create(String conversationId, String goal, List<String> stepTitles) {
        List<String> titles = stepTitles == null ? List.of() : stepTitles.stream()
                .map(TaskPlanService::clean)
                .filter(title -> !title.isBlank())
                .limit(MAX_STEPS)
                .toList();
        if (titles.isEmpty()) {
            throw new IllegalArgumentException("A plan needs at least one non-blank step.");
        }
        List<TaskPlan.Step> steps = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            steps.add(new TaskPlan.Step(i + 1, titles.get(i), i == 0 ? StepStatus.IN_PROGRESS : StepStatus.PENDING, ""));
        }
        TaskPlan plan = new TaskPlan(conversationId, clean(goal), steps, Instant.now().toString(), Instant.now().toString());
        save(plan);
        return plan;
    }

    /**
     * Updates one step. Completing a step automatically starts the next pending one.
     *
     * @param conversationId conversation id
     * @param stepNumber 1-based step number
     * @param status new status
     * @param note optional note (result, blocker, decision taken)
     * @return updated plan
     */
    public TaskPlan update(String conversationId, int stepNumber, StepStatus status, String note) {
        TaskPlan plan = find(conversationId).orElseThrow(() ->
                new IllegalArgumentException("No plan exists for this conversation. Create one with plan__create first."));
        if (stepNumber < 1 || stepNumber > plan.steps().size()) {
            throw new IllegalArgumentException("Step must be between 1 and " + plan.steps().size() + ".");
        }
        List<TaskPlan.Step> steps = new ArrayList<>(plan.steps());
        TaskPlan.Step current = steps.get(stepNumber - 1);
        String mergedNote = note == null || note.isBlank() ? current.note() : clean(note);
        steps.set(stepNumber - 1, new TaskPlan.Step(current.number(), current.title(), status, mergedNote));
        if (status.isTerminal() && steps.stream().noneMatch(step -> step.status() == StepStatus.IN_PROGRESS)) {
            for (int i = 0; i < steps.size(); i++) {
                TaskPlan.Step candidate = steps.get(i);
                if (candidate.status() == StepStatus.PENDING) {
                    steps.set(i, new TaskPlan.Step(candidate.number(), candidate.title(), StepStatus.IN_PROGRESS, candidate.note()));
                    break;
                }
            }
        }
        TaskPlan updated = new TaskPlan(plan.conversationId(), plan.goal(), steps, plan.createdAt(), Instant.now().toString());
        save(updated);
        return updated;
    }

    /**
     * Appends steps discovered while working.
     *
     * @param conversationId conversation id
     * @param stepTitles new step titles
     * @return updated plan
     */
    public TaskPlan addSteps(String conversationId, List<String> stepTitles) {
        TaskPlan plan = find(conversationId).orElseThrow(() ->
                new IllegalArgumentException("No plan exists for this conversation. Create one with plan__create first."));
        List<TaskPlan.Step> steps = new ArrayList<>(plan.steps());
        boolean anyActive = steps.stream().anyMatch(step -> step.status() == StepStatus.IN_PROGRESS);
        for (String raw : stepTitles == null ? List.<String>of() : stepTitles) {
            String title = clean(raw);
            if (title.isBlank() || steps.size() >= MAX_STEPS) {
                continue;
            }
            StepStatus status = anyActive ? StepStatus.PENDING : StepStatus.IN_PROGRESS;
            anyActive = true;
            steps.add(new TaskPlan.Step(steps.size() + 1, title, status, ""));
        }
        TaskPlan updated = new TaskPlan(plan.conversationId(), plan.goal(), steps, plan.createdAt(), Instant.now().toString());
        save(updated);
        return updated;
    }

    /**
     * Returns the plan of a conversation, loading it from disk when needed.
     *
     * @param conversationId conversation id
     * @return plan, when one exists
     */
    public Optional<TaskPlan> find(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        TaskPlan cached = plans.get(conversationId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path file = file(conversationId);
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            TaskPlan loaded = objectMapper.readValue(file.toFile(), TaskPlan.class);
            plans.put(conversationId, loaded);
            return Optional.of(loaded);
        } catch (IOException exception) {
            LOGGER.warn("[PLANNER] could not read plan conversationId={} error={}", conversationId, exception.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns the plan only while it still has unfinished steps.
     *
     * @param conversationId conversation id
     * @return unfinished plan
     */
    public Optional<TaskPlan> findUnfinished(String conversationId) {
        return find(conversationId).filter(plan -> !plan.isFinished());
    }

    /**
     * Removes the plan of a conversation.
     *
     * @param conversationId conversation id
     */
    public void clear(String conversationId) {
        if (conversationId == null) {
            return;
        }
        plans.remove(conversationId);
        Path file = file(conversationId);
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException exception) {
                LOGGER.debug("[PLANNER] could not delete plan file: {}", exception.getMessage());
            }
        }
    }

    private void save(TaskPlan plan) {
        plans.put(plan.conversationId(), plan);
        Path file = file(plan.conversationId());
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), plan);
        } catch (IOException exception) {
            LOGGER.warn("[PLANNER] could not persist plan conversationId={} error={}", plan.conversationId(), exception.getMessage());
        }
    }

    private Path file(String conversationId) {
        if (storageDir == null || conversationId == null || conversationId.isBlank()) {
            return null;
        }
        String safe = conversationId.replaceAll("[^A-Za-z0-9._-]", "_");
        return storageDir.resolve(safe + ".json");
    }

    private static String clean(String value) {
        String text = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }

    /**
     * Step status.
     */
    public enum StepStatus {
        PENDING, IN_PROGRESS, DONE, BLOCKED, SKIPPED;

        boolean isTerminal() {
            return this == DONE || this == SKIPPED || this == BLOCKED;
        }

        /**
         * Parses a lenient status value (also Polish words).
         *
         * @param raw raw value
         * @return status
         */
        public static StepStatus parse(String raw) {
            String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            return switch (value) {
                case "DONE", "COMPLETED", "COMPLETE", "FINISHED", "OK", "ZROBIONE", "GOTOWE" -> DONE;
                case "IN_PROGRESS", "ACTIVE", "STARTED", "WORKING", "W_TRAKCIE" -> IN_PROGRESS;
                case "BLOCKED", "FAILED", "ERROR", "ZABLOKOWANE" -> BLOCKED;
                case "SKIPPED", "SKIP", "CANCELLED", "POMINIETE" -> SKIPPED;
                case "PENDING", "TODO", "" -> PENDING;
                default -> throw new IllegalArgumentException("Unknown status '" + raw
                        + "'. Use pending, in_progress, done, blocked or skipped.");
            };
        }
    }
}
