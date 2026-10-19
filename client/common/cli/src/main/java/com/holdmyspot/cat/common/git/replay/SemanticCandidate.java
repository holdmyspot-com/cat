package com.holdmyspot.cat.common.git.replay;

import java.util.List;

/**
 * One independently observable behavior that justifies a retained topic output.
 *
 * @param id the stable candidate identifier
 * @param observableOutcome the behavior a user can observe
 * @param candidateDiscovery the behavior-first evidence that discovered the candidate across the range
 * @param splitCounterfactual the result of testing whether this behavior can remain separately usable
 * @param candidateUniverseIds reconciled planner and independent discovery records that justify this candidate
 * @param output the earliest retained output that contains the behavior
 * @param units the reviewer-named semantic units that implement the behavior
 * @param acceptanceCommand the exact command that proves the behavior from this output tree
 * @param prerequisites the conditions required before the behavior can be used
 * @param rejectedEarlierOutputs earlier outputs that cannot contain the behavior
 * @param combinedCandidateBlockers reasons this behavior remains with other behavior candidates in one output
 * @param evidence the behavior-specific placement evidence
 */
public record SemanticCandidate(String id, String observableOutcome, String candidateDiscovery,
	String splitCounterfactual, List<String> candidateUniverseIds, String output,
	List<SemanticUnitReference> units, List<String> acceptanceCommand,
	List<String> prerequisites, List<RejectedEarlierOutput> rejectedEarlierOutputs,
	List<CombinedCandidateBlocker> combinedCandidateBlockers, String evidence)
{
}
