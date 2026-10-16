package io.github.cowwoc.cat.common.git.replay;

/**
 * One exact-path candidate paired with the reviewer's recorded semantic disposition.
 *
 * @param inventoryId the changed inventory item
 * @param path the changed repository-relative path
 * @param status indicates whether a reviewer has recorded a decision
 * @param classification indicates whether the candidate is the same semantic unit, if reviewed
 * @param destination the selected output when the unit is the same, if reviewed
 */
record OriginReviewAudit(String inventoryId, String path, String status, String classification, String destination)
{
}
