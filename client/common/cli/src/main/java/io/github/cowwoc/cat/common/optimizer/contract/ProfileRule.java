package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.List;

/**
 * Computes a proposal's model and reasoning-effort combinations from its requirements and prompt tests. CAT performs
 * this computation instead of the agent that derives the proposal.
 */
@FunctionalInterface
public interface ProfileRule
{
	/**
	 * Computes the trial profiles.
	 *
	 * @param requirements every requirement of the proposal
	 * @param tests every prompt test of the proposal
	 * @return the trial profiles
	 * @throws IllegalArgumentException if the profiles cannot be computed, for example because a restriction leaves a
	 *   test without a profile
	 */
	TrialProfiles profiles(List<Requirement> requirements, List<PromptTest> tests);
}
