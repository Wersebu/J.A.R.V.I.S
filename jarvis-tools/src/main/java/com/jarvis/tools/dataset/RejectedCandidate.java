package com.jarvis.tools.dataset;

/**
 * A candidate record rejected during {@link StoreAuditDatasetService#createDataset}, with the
 * reason - so the model sees exactly what was dropped and why, instead of a silently smaller
 * dataset.
 *
 * @param index position of the rejected candidate in the submitted list
 * @param reason human-readable rejection reason
 */
public record RejectedCandidate(int index, String reason) {
}
