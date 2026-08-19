package com.jarvis.tools.workflow;

import java.util.List;
import java.util.Optional;

/**
 * Chains multiple {@link WorkflowCompletionValidator}s behind the single interface {@code
 * NativeToolLoopService} talks to, so the agent loop itself never needs to know how many
 * workflow-specific or generic completion checks are actually active. Returns the first
 * non-complete assessment found, in order; {@link CompletionAssessment#ok()} only when every
 * validator agrees there is nothing to gate on.
 */
public class CompositeWorkflowCompletionValidator implements WorkflowCompletionValidator {

    private final List<WorkflowCompletionValidator> validators;

    /**
     * Creates the composite validator.
     *
     * @param validators validators to chain, checked in order
     */
    public CompositeWorkflowCompletionValidator(List<WorkflowCompletionValidator> validators) {
        this.validators = List.copyOf(validators);
    }

    @Override
    public CompletionAssessment assess(WorkflowCompletionContext context) {
        for (WorkflowCompletionValidator validator : validators) {
            CompletionAssessment assessment = validator.assess(context);
            if (!assessment.complete()) {
                return assessment;
            }
        }
        return CompletionAssessment.ok();
    }

    @Override
    public Optional<String> requiredDocumentPath() {
        for (WorkflowCompletionValidator validator : validators) {
            Optional<String> path = validator.requiredDocumentPath();
            if (path.isPresent()) {
                return path;
            }
        }
        return Optional.empty();
    }
}
