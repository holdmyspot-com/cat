package io.github.cowwoc.cat.common.git.replay;

/**
 * A reviewer classification of one raw deletion or Git-reported rename.
 *
 * @param id the transition fact being classified
 * @param classification {@code independent-removal} or {@code related-transition}
 * @param relatedInventoryId the related inventory item for a related transition
 * @param evidence the semantic evidence supporting the classification
 */
record PathTransitionDecision(String id, String classification, String relatedInventoryId, String evidence)
{
}
