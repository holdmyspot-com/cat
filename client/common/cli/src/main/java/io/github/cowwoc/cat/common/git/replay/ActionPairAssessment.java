package io.github.cowwoc.cat.common.git.replay;

import java.util.List;

/**
 * One pairwise decision about whether two observable behaviors can remain separate.
 *
 * @param firstCandidateId one observable behavior
 * @param secondCandidateId the other observable behavior
 * @param disposition either {@code separate} or {@code combined}
 * @param removalResult the observed result of removing one contract from the other snapshot
 * @param splitEnablingArtifacts the smallest artifacts used to test the split
 * @param missingPrerequisite the exact final-state condition that blocks a combined pair's split, or {@code null}
 */
record ActionPairAssessment(String firstCandidateId, String secondCandidateId, String disposition,
	String removalResult, List<String> splitEnablingArtifacts, String missingPrerequisite)
{
}
