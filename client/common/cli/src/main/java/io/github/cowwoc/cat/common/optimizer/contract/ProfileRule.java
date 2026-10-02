package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.List;

/**
 * Computes a proposal's model and reasoning-effort combinations from its requirements and categories. CAT performs
 * this computation instead of the agent that derives the proposal (design &sect;6.1).
 */
@FunctionalInterface
public interface ProfileRule
{
	/**
	 * Computes the trial profiles.
	 *
	 * @param requirements every requirement of the proposal
	 * @param categories every category of the proposal
	 * @return the trial profiles
	 * @throws IllegalArgumentException if the profiles cannot be computed, for example because a restriction leaves a
	 *   category without a profile
	 */
	TrialProfiles profiles(List<Requirement> requirements, List<Category> categories);
}
