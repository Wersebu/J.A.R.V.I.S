package com.jarvis.tools.workflow;

import java.util.regex.Pattern;

/**
 * Workflow-agnostic completion gate: blocks a proposed {@code FINAL_ANSWER} when every tool call
 * that actually succeeded this loop was bootstrap-only (discovery/selection - see {@link
 * ToolOperationRole#isBootstrap()}) AND the model's own proposed content/reasoning text admits the
 * answer is not actually there yet.
 *
 * <p>This exists for the general case a specific stateful workflow (like Store Audit) does not
 * cover: a model calls a preparatory tool (e.g. "list the open Roblox Studio sessions"), gets a
 * successful result, and then answers using only that bootstrap result even though the user asked
 * for something the bootstrap call was never going to contain (e.g. "list the project's folders").
 * A successful bootstrap tool call is never itself proof the user's goal was reached.</p>
 *
 * <p>Deliberately conservative: this never blocks purely because the last tool was bootstrap-only -
 * a bootstrap-only result can genuinely be a complete, correct answer to some questions (e.g. "is
 * Roblox Studio currently connected?"). It only blocks when the model's own text signals the
 * insufficiency itself, so a legitimate short answer is never rejected.</p>
 *
 * <h2>Known limitation - this is a safety-net fallback, not the real fix</h2>
 *
 * <p>This validator is phrase-based: it only catches the case where the model's own words admit
 * the answer is incomplete. It is blind to a <b>confidently wrong</b> answer - a model that calls
 * only a bootstrap tool and then states an incorrect final answer with no hedging at all (e.g.
 * asked to list a project's folders, calling only a session-listing tool, then answering "the
 * available folder is Untitled Project" with no admission anything is missing) sails straight
 * through unblocked, because there is no insufficiency phrase in its text for the pattern to catch.
 * See {@code NativeToolLoopServiceConfidentWrongAnswerLimitationTest} for a scripted regression
 * that pins this exact gap down and documents it, rather than hiding it.</p>
 *
 * <p>It is also inherently model-dependent in a second way: it scans the model's {@code content}
 * <i>and</i> {@code thinking} channel for insufficiency phrases, but a provider/model combination
 * may not expose a thinking channel at all (disabled, unsupported, or simply empty for that turn),
 * and phrasing itself may shift language or wording in ways the pattern never anticipated. This
 * validator degrades gracefully in that case (it just never blocks, the same as any other pattern
 * miss) - it never depends on thinking being present to function safely, but it also cannot use an
 * absent thinking channel as extra evidence.</p>
 *
 * <p>The actual fix for both gaps is an explicit, model-declared <b>Goal Contract</b> created before
 * the first tool call (original goal, required outcome, completion criteria) carried through the
 * loop as real state, plus a short structured completion-verification turn - a genuine model
 * self-assessment against that contract (COMPLETE/CONTINUE/BLOCKED), not a regex over free text -
 * before any {@code FINAL_ANSWER} is accepted. That mechanism is designed (see the {@code
 * com.jarvis.tools.workflow.goal} package: {@code GoalContract}, {@code CompletionCriterion}, {@code
 * CompletionVerification}, {@code GoalCompletionVerifier}) but deliberately not wired into the loop
 * yet - it changes the loop's call-budget/latency shape and needs its own dedicated stage rather
 * than being bolted onto this fix. Until it lands, this phrase-based validator remains an imperfect
 * but strictly-additive safety net: it catches a real, previously-unhandled failure mode (the
 * Roblox folder-listing bug) without weakening anything that worked before it existed.</p>
 */
public class GenericGoalCompletionValidator implements WorkflowCompletionValidator {

    /**
     * Polish and English phrasings a model tends to use when it knows its own answer did not
     * actually satisfy the request - deliberately broad rather than exhaustive; a miss here simply
     * means this gate stays silent, which is the safe failure direction for a heuristic like this.
     */
    private static final Pattern INSUFFICIENCY_PATTERN = Pattern.compile(
            "(?i)(nie zawiera|to nie jest (lista|odpowiedz|folder|wynik)|to tylko|brakuje (mi|jeszcze)|"
                    + "nie mam jeszcze|potrzebuj[ea] (kolejnego|innego|jeszcze) narz|musz[ea] (jeszcze|dodatkowo)|"
                    + "nie znalaz|nie odpowiada na|jeszcze nie (mam|uzyska)|nie uzyskal|"
                    + "doesn't (actually )?contain|does not (actually )?contain|not the (list|answer|folders?|data)|"
                    + "only (found|have|shows|returned)|i only|need(s)? (another|a different|more) tool|"
                    + "i (still )?need to|not enough (information|data|evidence)|insufficient|incomplete|"
                    + "is missing|haven't (found|retrieved|gotten)|not yet complete|does not answer)");

    @Override
    public CompletionAssessment assess(WorkflowCompletionContext context) {
        if (context.toolCallCount() <= 0 || !context.bootstrapOnlyEvidence()) {
            return CompletionAssessment.ok();
        }
        if (!INSUFFICIENCY_PATTERN.matcher(context.proposedFinalText()).find()) {
            return CompletionAssessment.ok();
        }
        String guidance = """
                The tool result(s) collected so far only performed discovery/selection (for example:
                listing available sessions, connections, or instances, or picking one to work with) - a
                successful bootstrap tool call is never itself the answer to the user's actual request.
                Your own response indicates the requested information has not actually been retrieved
                yet. Original user request: "%s"
                Call a tool that actually reads, searches, inspects, or otherwise retrieves the specific
                information the user asked for, then answer using that real result - do not restate the
                bootstrap tool's result as if it were the answer.
                """.formatted(context.originalUserRequest());
        return new CompletionAssessment(false, "BOOTSTRAP_ONLY_EVIDENCE_INSUFFICIENT_ANSWER", guidance);
    }
}
