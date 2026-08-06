package com.jarvis.tools.knowledge.filing;

import com.jarvis.knowledge.retrieval.RetrievalResult;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceTree;

/**
 * Plans where extracted knowledge should be filed.
 */
public interface KnowledgeDestinationPlanner {

    /**
     * Builds a destination plan.
     *
     * @param knowledge extracted knowledge
     * @param tree current workspace tree
     * @param searchResult search candidates
     * @return filing plan
     */
    KnowledgeDestinationPlan plan(ExtractedKnowledge knowledge, KnowledgeWorkspaceTree tree, RetrievalResult searchResult);
}
