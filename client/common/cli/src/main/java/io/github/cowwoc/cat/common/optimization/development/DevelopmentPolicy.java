package io.github.cowwoc.cat.common.optimization.development;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies simulation-selected paired-comparison, readiness, and plateau thresholds without agent judgment.
 */
public final class DevelopmentPolicy
{
	private final String identifier;
	private final int initialPairs;
	private final int additionalPairs;
	private final int maximumPairs;
	private final int maximumSurvivors;
	private final int promotionNetWins;
	private final int materialRegressionNetLosses;
	private final int readinessTrials;
	private final int readinessMaximumFailures;
	private final int plateauRounds;
	private final double plateauMinimumImprovement;

	/**
	 * Creates one complete versioned development policy.
	 *
	 * @param identifier the policy identifier
	 * @param initialPairs the minimum pairs before a terminal comparison
	 * @param additionalPairs the pair increment when evidence is inconclusive
	 * @param maximumPairs the maximum pairs in one comparison
	 * @param maximumSurvivors the maximum challengers that may receive broad comparison
	 * @param promotionNetWins the minimum aggregate wins minus losses for promotion
	 * @param materialRegressionNetLosses the family losses minus wins that require repair
	 * @param readinessTrials the fresh readiness trials required per family
	 * @param readinessMaximumFailures the failures permitted in each readiness family
	 * @param plateauRounds the completed improvement intervals considered for plateau
	 * @param plateauMinimumImprovement the minimum total improvement over those intervals
	 */
	public DevelopmentPolicy(String identifier, int initialPairs, int additionalPairs, int maximumPairs,
		int maximumSurvivors, int promotionNetWins, int materialRegressionNetLosses, int readinessTrials,
		int readinessMaximumFailures, int plateauRounds, double plateauMinimumImprovement)
	{
		this.identifier = requireText(identifier, "identifier");
		if (initialPairs <= 0)
			throw new IllegalArgumentException("initialPairs must be positive: " + initialPairs);
		if (additionalPairs <= 0)
			throw new IllegalArgumentException("additionalPairs must be positive: " + additionalPairs);
		if (maximumPairs < initialPairs)
			throw new IllegalArgumentException("maximumPairs must be at least initialPairs: " + maximumPairs);
		if (maximumSurvivors <= 0)
			throw new IllegalArgumentException("maximumSurvivors must be positive: " + maximumSurvivors);
		if (promotionNetWins <= 0)
			throw new IllegalArgumentException("promotionNetWins must be positive: " + promotionNetWins);
		if (materialRegressionNetLosses <= 0)
		{
			throw new IllegalArgumentException("materialRegressionNetLosses must be positive: " +
				materialRegressionNetLosses);
		}
		if (readinessTrials <= 0)
			throw new IllegalArgumentException("readinessTrials must be positive: " + readinessTrials);
		if (readinessMaximumFailures < 0 || readinessMaximumFailures >= readinessTrials)
			throw new IllegalArgumentException("readinessMaximumFailures must be between zero and readinessTrials");
		if (plateauRounds <= 0)
			throw new IllegalArgumentException("plateauRounds must be positive: " + plateauRounds);
		if (plateauMinimumImprovement <= 0 || plateauMinimumImprovement >= 1)
			throw new IllegalArgumentException("plateauMinimumImprovement must be between zero and one");
		this.initialPairs = initialPairs;
		this.additionalPairs = additionalPairs;
		this.maximumPairs = maximumPairs;
		this.maximumSurvivors = maximumSurvivors;
		this.promotionNetWins = promotionNetWins;
		this.materialRegressionNetLosses = materialRegressionNetLosses;
		this.readinessTrials = readinessTrials;
		this.readinessMaximumFailures = readinessMaximumFailures;
		this.plateauRounds = plateauRounds;
		this.plateauMinimumImprovement = plateauMinimumImprovement;
	}

	/**
	 * Applies the paired A/B comparison policy.
	 *
	 * @param families the paired outcomes by family
	 * @return the next deterministic comparison action
	 */
	public ComparisonDecision compare(List<FamilyPairs> families)
	{
		List<FamilyPairs> values = sortedFamilies(families);
		if (values.isEmpty())
			throw new IllegalArgumentException("families must not be empty");

		int minimumExposure = Integer.MAX_VALUE;
		int totalWins = 0;
		int totalLosses = 0;
		List<String> regressions = new ArrayList<>();
		for (FamilyPairs family : values)
		{
			minimumExposure = Math.min(minimumExposure, family.pairCount());
			totalWins += family.challengerWins();
			totalLosses += family.challengerLosses();
			if (family.pairCount() >= initialPairs &&
				family.challengerLosses() - family.challengerWins() >= materialRegressionNetLosses)
			{
				regressions.add(family.familyIdentifier());
			}
		}
		if (!regressions.isEmpty())
			return new ComparisonDecision(ComparisonAction.REPAIR_REGRESSION, 0, regressions);
		if (minimumExposure < initialPairs)
		{
			int missing = initialPairs - minimumExposure;
			return new ComparisonDecision(ComparisonAction.MORE_PAIRS, Math.min(additionalPairs, missing), List.of());
		}
		if (totalWins - totalLosses >= promotionNetWins)
			return new ComparisonDecision(ComparisonAction.PROMOTE, 0, List.of());
		if (minimumExposure >= maximumPairs)
			return new ComparisonDecision(ComparisonAction.REJECT, 0, List.of());
		return new ComparisonDecision(ComparisonAction.MORE_PAIRS,
			Math.min(additionalPairs, maximumPairs - minimumExposure), List.of());
	}

	/**
	 * Applies the equal per-prompt-test-family promotion rule using the challenger's preassigned error allowance.
	 *
	 * <p>This is the selection-aware comparison path. It promotes only when every family establishes a challenger
	 * advantage above one half and requests repair when any family establishes the corresponding loss direction.
	 * Tied paired outcomes are ignored by {@link PairedPromptTestFamilyEngine}.</p>
	 *
	 * @param families the paired outcomes by family
	 * @param allowance the challenger's immutable lineage allowance
	 * @return the next deterministic comparison action
	 */
	public ComparisonDecision compare(List<FamilyPairs> families, double allowance)
	{
		List<FamilyPairs> values = sortedFamilies(families);
		if (values.isEmpty())
			throw new IllegalArgumentException("families must not be empty");

		int minimumExposure = Integer.MAX_VALUE;
		for (FamilyPairs family : values)
			minimumExposure = Math.min(minimumExposure, family.pairCount());
		if (minimumExposure < initialPairs)
		{
			int missing = initialPairs - minimumExposure;
			return new ComparisonDecision(ComparisonAction.MORE_PAIRS, Math.min(additionalPairs, missing), List.of());
		}

		PairedPromptTestFamilyEngine.Decision paired = PairedPromptTestFamilyEngine.decide(values, allowance);
		if (paired == PairedPromptTestFamilyEngine.Decision.PROMOTE)
			return new ComparisonDecision(ComparisonAction.PROMOTE, 0, List.of());
		if (paired == PairedPromptTestFamilyEngine.Decision.REPAIR_REGRESSION)
		{
			List<String> regressions = new ArrayList<>();
			for (FamilyPairs family : values)
			{
				if (PairedPromptTestFamilyEngine.decide(List.of(family), allowance) ==
					PairedPromptTestFamilyEngine.Decision.REPAIR_REGRESSION)
				{
					regressions.add(family.familyIdentifier());
				}
			}
			return new ComparisonDecision(ComparisonAction.REPAIR_REGRESSION, 0, regressions);
		}
		if (minimumExposure >= maximumPairs)
			return new ComparisonDecision(ComparisonAction.REJECT, 0, List.of());
		return new ComparisonDecision(ComparisonAction.MORE_PAIRS,
			Math.min(additionalPairs, maximumPairs - minimumExposure), List.of());
	}

	/**
	 * Applies the fresh readiness-batch rule.
	 *
	 * @param families the readiness outcomes by family
	 * @return whether to start formal evidence or continue development
	 */
	public ReadinessDecision readiness(List<FamilyReadiness> families)
	{
		List<FamilyReadiness> values = sortedFamilies(families);
		if (values.isEmpty())
			throw new IllegalArgumentException("families must not be empty");

		List<String> limitingFamilies = new ArrayList<>();
		for (FamilyReadiness family : values)
		{
			int failures = family.completeTrials() - family.passingTrials();
			if (family.completeTrials() < readinessTrials || failures > readinessMaximumFailures)
				limitingFamilies.add(family.familyIdentifier());
		}
		if (limitingFamilies.isEmpty())
			return new ReadinessDecision(ReadinessAction.START_FORMAL_BATCH, List.of());
		return new ReadinessDecision(ReadinessAction.CONTINUE_DEVELOPMENT, limitingFamilies);
	}

	/**
	 * Applies the plateau rule to comparable weakest-family development scores.
	 *
	 * @param scores the scores in round order under one unchanged contract and environment
	 * @return whether the required consecutive rounds have plateaued
	 */
	public PlateauDecision plateau(List<Double> scores)
	{
		if (scores == null)
			throw new NullPointerException("scores must not be null");
		for (Double score : scores)
		{
			if (score == null)
				throw new NullPointerException("scores must not contain null elements");
			if (Double.isNaN(score) || score < 0 || score > 1)
				throw new IllegalArgumentException("scores must be between zero and one: " + score);
		}
		if (scores.size() <= plateauRounds)
			return new PlateauDecision(false, 0);

		int first = scores.size() - plateauRounds - 1;
		double improvement = scores.getLast() - scores.get(first);
		return new PlateauDecision(improvement < plateauMinimumImprovement, improvement);
	}

	/**
	 * Returns this policy's immutable identifier.
	 *
	 * @return the policy identifier
	 */
	public String identifier()
	{
		return identifier;
	}

	/**
	 * Returns the minimum paired promptTests before a terminal comparison.
	 *
	 * @return the initial pair count
	 */
	public int initialPairs()
	{
		return initialPairs;
	}

	/**
	 * Returns the pair increment for an inconclusive comparison.
	 *
	 * @return the additional pair count
	 */
	public int additionalPairs()
	{
		return additionalPairs;
	}

	/**
	 * Returns the maximum pairs in one candidate comparison.
	 *
	 * @return the maximum pair count
	 */
	public int maximumPairs()
	{
		return maximumPairs;
	}

	/**
	 * Returns the maximum challengers that may receive broad all-family comparison.
	 *
	 * @return the survivor count
	 */
	public int maximumSurvivors()
	{
		return maximumSurvivors;
	}

	/**
	 * Returns the net-win threshold for promotion.
	 *
	 * @return the promotion net wins
	 */
	public int promotionNetWins()
	{
		return promotionNetWins;
	}

	/**
	 * Returns the family-scoped net-loss threshold for material regression.
	 *
	 * @return the material-regression net losses
	 */
	public int materialRegressionNetLosses()
	{
		return materialRegressionNetLosses;
	}

	/**
	 * Returns the fresh readiness trials required per family.
	 *
	 * @return the readiness trial count
	 */
	public int readinessTrials()
	{
		return readinessTrials;
	}

	/**
	 * Returns the readiness failures permitted per family.
	 *
	 * @return the maximum readiness failures
	 */
	public int readinessMaximumFailures()
	{
		return readinessMaximumFailures;
	}

	/**
	 * Returns the completed improvement intervals in the plateau window.
	 *
	 * @return the plateau interval count
	 */
	public int plateauRounds()
	{
		return plateauRounds;
	}

	/**
	 * Returns the minimum total improvement that avoids a plateau result.
	 *
	 * @return the plateau improvement boundary
	 */
	public double plateauMinimumImprovement()
	{
		return plateauMinimumImprovement;
	}

	/**
	 * Sorts family values and rejects duplicate identifiers.
	 *
	 * @param values the family values
	 * @param <T> a family-value type
	 * @return the immutable sorted values
	 */
	private static <T extends FamilyValue> List<T> sortedFamilies(List<T> values)
	{
		if (values == null)
			throw new NullPointerException("families must not be null");
		List<T> result = new ArrayList<>(values);
		for (T value : result)
		{
			if (value == null)
				throw new NullPointerException("families must not contain null elements");
		}
		result.sort(Comparator.comparing(FamilyValue::familyIdentifier));
		String previous = null;
		for (T value : result)
		{
			if (value.familyIdentifier().equals(previous))
				throw new IllegalArgumentException("Duplicate family identifier: " + previous);
			previous = value.familyIdentifier();
		}
		return List.copyOf(result);
	}

	/**
	 * Validates open text.
	 *
	 * @param value the value
	 * @param name the parameter name
	 * @return the unchanged value
	 */
	private static String requireText(String value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Identifies a family-scoped policy input.
	 */
	@FunctionalInterface
	private interface FamilyValue
	{
		/**
		 * Returns the family identifier.
		 *
		 * @return the family identifier
		 */
		String familyIdentifier();
	}

	/**
	 * Names a paired-comparison decision.
	 */
	public enum ComparisonAction
	{
		PROMOTE,
		REJECT,
		MORE_PAIRS,
		REPAIR_REGRESSION
	}

	/**
	 * Names a readiness decision.
	 */
	public enum ReadinessAction
	{
		START_FORMAL_BATCH,
		CONTINUE_DEVELOPMENT
	}

	/**
	 * Holds one family's paired outcomes.
	 *
	 * @param familyIdentifier the family identifier
	 * @param challengerWins promptTests passed only by the challenger
	 * @param challengerLosses promptTests passed only by the champion
	 * @param ties promptTests with equal outcomes
	 */
	public record FamilyPairs(String familyIdentifier, int challengerWins, int challengerLosses, int ties)
		implements FamilyValue
	{
		/**
		 * Validates one family comparison.
		 *
		 * @param familyIdentifier the family identifier
		 * @param challengerWins promptTests passed only by the challenger
		 * @param challengerLosses promptTests passed only by the champion
		 * @param ties promptTests with equal outcomes
		 */
		public FamilyPairs
		{
			familyIdentifier = requireText(familyIdentifier, "familyIdentifier");
			if (challengerWins < 0 || challengerLosses < 0 || ties < 0)
				throw new IllegalArgumentException("Paired outcome counts must not be negative");
		}

		/**
		 * Returns the number of shared promptTests in this comparison.
		 *
		 * @return the pair count
		 */
		public int pairCount()
		{
			return Math.addExact(Math.addExact(challengerWins, challengerLosses), ties);
		}
	}

	/**
	 * Holds one family's fresh readiness outcomes.
	 *
	 * @param familyIdentifier the family identifier
	 * @param passingTrials the passing trials
	 * @param completeTrials the completed trials
	 */
	public record FamilyReadiness(String familyIdentifier, int passingTrials, int completeTrials)
		implements FamilyValue
	{
		/**
		 * Validates one family readiness result.
		 *
		 * @param familyIdentifier the family identifier
		 * @param passingTrials the passing trials
		 * @param completeTrials the completed trials
		 */
		public FamilyReadiness
		{
			familyIdentifier = requireText(familyIdentifier, "familyIdentifier");
			if (completeTrials < 0)
				throw new IllegalArgumentException("completeTrials must not be negative: " + completeTrials);
			if (passingTrials < 0 || passingTrials > completeTrials)
				throw new IllegalArgumentException("passingTrials must be between zero and completeTrials");
		}
	}

	/**
	 * Holds one paired-comparison action.
	 *
	 * @param action the next action
	 * @param additionalPairs the exact new pairs requested, or zero for another action
	 * @param affectedFamilies the families requiring regression repair
	 */
	public record ComparisonDecision(ComparisonAction action, int additionalPairs, List<String> affectedFamilies)
	{
		/**
		 * Validates and snapshots one comparison decision.
		 *
		 * @param action the next action
		 * @param additionalPairs the exact new pairs requested, or zero for another action
		 * @param affectedFamilies the families requiring regression repair
		 */
		public ComparisonDecision
		{
			if (action == null)
				throw new NullPointerException("action must not be null");
			if (additionalPairs < 0)
				throw new IllegalArgumentException("additionalPairs must not be negative");
			affectedFamilies = List.copyOf(affectedFamilies);
		}
	}

	/**
	 * Holds one readiness action.
	 *
	 * @param action the next action
	 * @param limitingFamilies the families that have not met the gate
	 */
	public record ReadinessDecision(ReadinessAction action, List<String> limitingFamilies)
	{
		/**
		 * Validates and snapshots one readiness decision.
		 *
		 * @param action the next action
		 * @param limitingFamilies the families that have not met the gate
		 */
		public ReadinessDecision
		{
			if (action == null)
				throw new NullPointerException("action must not be null");
			limitingFamilies = List.copyOf(limitingFamilies);
		}
	}

	/**
	 * Holds the plateau decision and measured improvement.
	 *
	 * @param plateau whether the policy detected a plateau
	 * @param observedImprovement the comparable score improvement across the policy window
	 */
	public record PlateauDecision(boolean plateau, double observedImprovement)
	{
	}
}
