package io.github.cowwoc.cat.common.git.replay;

/**
 * Reviewer-owned authorization evidence for the immutable replay range.
 *
 * @param mode either {@code whole-range} or {@code fixed-prefix}
 * @param base the captured replay base
 * @param tip the captured replay tip
 * @param governingRuleRevision the rule revision used to make the scope decision
 * @param authorizationEvidence the user-authority evidence for the selected scope
 * @param fixedPrefixTip the last preserved source when {@code mode} is {@code fixed-prefix}
 * @param priorCompletionArtifact the committed prior-review evidence when {@code mode} is {@code fixed-prefix}
 */
record ScopeAttestation(String mode, String base, String tip, String governingRuleRevision,
	String authorizationEvidence, String fixedPrefixTip, String priorCompletionArtifact)
{
}
