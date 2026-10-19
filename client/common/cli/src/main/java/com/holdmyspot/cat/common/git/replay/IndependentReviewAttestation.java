package com.holdmyspot.cat.common.git.replay;

/**
 * The external handoff facts for independent candidate discovery.
 *
 * @param reviewer the independent reviewer identity
 * @param base the captured replay base
 * @param tip the captured replay tip
 * @param workflowRevision the workflow revision used for review
 * @param rawDiffHandoff the immutable raw-diff handoff reference
 * @param candidateUniverseSha256 the immutable independent candidate-universe bytes
 * @param actionResultMatrixSha256 the immutable independent action-result matrix bytes
 * @param unprimed indicates whether the reviewer lacked planner placement information
 */
record IndependentReviewAttestation(String reviewer, String base, String tip, String workflowRevision,
	String rawDiffHandoff, String candidateUniverseSha256, String actionResultMatrixSha256, boolean unprimed)
{
}
