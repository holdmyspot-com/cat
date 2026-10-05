package io.github.cowwoc.cat.common.git.replay;

import java.util.List;

/**
 * A reviewer classification for one changed existing path with earlier exact-path predecessors.
 *
 * @param inventoryId the changed inventory item
 * @param predecessors the Git-generated predecessor sources, supplied by the CLI when recording the decision
 * @param classification {@code same_unit} when a predecessor is the changed unit, or {@code different_unit} when the
 *        shared path is used by a newly introduced independent responsibility
 * @param affectedUnit the exact type, method, rule, test, configuration element, or document section being changed
 * @param owningResponsibility the responsibility whose final snapshot owns the affected unit
 * @param predecessorRelationship {@code corrects_predecessor}, {@code tests_predecessor},
 *        {@code documents_predecessor}, {@code configures_predecessor}, {@code removes_invalid_predecessor}, or
 *        {@code introduces_independent_responsibility}
 * @param destination the selected dependency-complete output for a same-unit decision
 * @param rejectedDestinations earlier candidate outputs rejected for a documented missing prerequisite
 * @param evidence the semantic distinction or reviewed placement evidence
 */
record OriginReview(String inventoryId, List<String> predecessors, String classification, String affectedUnit,
	String owningResponsibility, String predecessorRelationship, String destination,
	List<RejectedDestination> rejectedDestinations, String evidence)
{
}
