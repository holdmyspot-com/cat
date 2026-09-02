package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.optimization.certification.Statistics;
import java.util.List;
import java.util.Objects;

/** Applies one equal threshold to every prompt-test family's non-tied paired outcomes. */
public final class PairedPromptTestFamilyEngine
{
	/** Prevents construction of this static policy component. */
	private PairedPromptTestFamilyEngine()
	{
	}

	/**
	 * Decides whether every family establishes a challenger advantage at the supplied error allowance.
	 *
	 * @param families the complete family comparison results
	 * @param allowance the fixed challenger error allowance
	 * @return promote only when every family has a lower bound above one half
	 */
	public static Decision decide(List<DevelopmentPolicy.FamilyPairs> families, double allowance)
	{
		Objects.requireNonNull(families, "families must not be null");
		if (families.isEmpty())
			throw new IllegalArgumentException("families must not be empty");
		if (allowance <= 0 || allowance >= 1)
			throw new IllegalArgumentException("allowance must be between zero and one");
		boolean promote = true;
		boolean regression = false;
		for (DevelopmentPolicy.FamilyPairs family : families)
		{
			Objects.requireNonNull(family, "families must not contain null elements");
			int outcomes = family.challengerWins() + family.challengerLosses();
			if (outcomes == 0)
			{
				promote = false;
				continue;
			}
			if (Statistics.sequentialLowerConfidenceBound(family.challengerWins(), outcomes, allowance) <= 0.5)
				promote = false;
			if (Statistics.sequentialLowerConfidenceBound(family.challengerLosses(), outcomes, allowance) > 0.5)
				regression = true;
		}
		if (regression)
			return Decision.REPAIR_REGRESSION;
		if (promote)
			return Decision.PROMOTE;
		return Decision.MORE_PAIRS;
	}

	/** Names the only immediate paired-comparison decisions. */
	public enum Decision
	{
		PROMOTE,
		REPAIR_REGRESSION,
		MORE_PAIRS
	}
}
