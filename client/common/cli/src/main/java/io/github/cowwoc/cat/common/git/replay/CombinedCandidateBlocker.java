package io.github.cowwoc.cat.common.git.replay;

/**
 * A specific missing condition that prevents two behavior candidates from becoming separate outputs.
 *
 * @param candidateId the other behavior candidate retained in the same output
 * @param reason the missing condition that prevents the split
 */
public record CombinedCandidateBlocker(String candidateId, String reason)
{
}
