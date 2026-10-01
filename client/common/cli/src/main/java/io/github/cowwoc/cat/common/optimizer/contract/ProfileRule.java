package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.List;

/**
 * Computes the trial profiles of a proposal from its requirements and categories, which CAT does instead of the
 * deriver (design &sect;6.1).
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
