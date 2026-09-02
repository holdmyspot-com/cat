package io.github.cowwoc.cat.common.optimization.development;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Selects executable development thresholds through a fixed, untouched synthetic calibration contract.
 *
 * <p>
 * The paired simulation samples the champion and challenger independently on each shared prompt_test. This is a
 * conservative reference model for the selector, not a claim about a customer's unknown production distribution.
 * Every selected policy and simulation manifest must be retained together.
 */
public final class DevelopmentPolicySimulation
{
	/**
	 * Prevents construction of this static simulation owner.
	 */
	private DevelopmentPolicySimulation()
	{
	}

	/**
	 * Evaluates every proposed policy and returns the least-evaluation eligible policy.
	 *
	 * @param policies the versioned policy candidates
	 * @param parameters the fixed untouched simulation contract
	 * @return the selected policy and complete policy metrics, or a blocked result
	 */
	public static Result select(List<DevelopmentPolicy> policies, Parameters parameters)
	{
		Objects.requireNonNull(policies, "policies must not be null");
		Objects.requireNonNull(parameters, "parameters must not be null");
		if (policies.isEmpty())
			throw new IllegalArgumentException("policies must not be empty");
		Set<String> identifiers = new HashSet<>();
		List<Metrics> metrics = new ArrayList<>();
		for (DevelopmentPolicy policy : policies)
		{
			Objects.requireNonNull(policy, "policies must not contain null elements");
			if (!identifiers.add(policy.identifier()))
				throw new IllegalArgumentException("Duplicate development policy identifier: " + policy.identifier());
			metrics.add(simulate(policy, parameters));
		}
		metrics.sort(Comparator.comparing(Metrics::policyIdentifier));
		Optional<DevelopmentPolicy> selected = policies.stream().filter(policy -> metrics.stream().
			anyMatch(value -> value.policyIdentifier().equals(policy.identifier()) && value.eligible())).min(
			Comparator.comparingDouble((DevelopmentPolicy policy) -> metric(metrics, policy).expectedEvaluations()).
				thenComparing(DevelopmentPolicy::identifier));
		if (selected.isEmpty())
			return new Result(Status.BLOCKED, Optional.empty(), metrics, parameters);
		return new Result(Status.SELECTED, selected, metrics, parameters);
	}

	/**
	 * Runs every calibration case for one policy.
	 *
	 * @param policy the policy
	 * @param parameters the simulation contract
	 * @return the complete metrics
	 */
	private static Metrics simulate(DevelopmentPolicy policy, Parameters parameters)
	{
		PairMetrics improved = paired(policy, parameters, parameters.improvedCompliance(), parameters.seed());
		PairMetrics regressed = paired(policy, parameters, parameters.regressedCompliance(),
			parameters.seed() ^ 0x9e3779b97f4a7c15L);
		double readyAcceptanceRate = readiness(policy, parameters, parameters.readyCompliance(),
			parameters.seed() ^ 0x243f6a8885a308d3L);
		double unreadyAcceptanceRate = readiness(policy, parameters, parameters.unreadyCompliance(),
			parameters.seed() ^ 0xb7e151628aed2a6bL);
		boolean flatPlateauDetected = policy.plateau(flatScores(policy)).plateau();
		boolean progressRejectedAsPlateau = policy.plateau(progressScores(policy)).plateau();

		List<EligibilityFailure> failures = new ArrayList<>();
		if (improved.promotionRate() < parameters.minimumImprovementPromotionRate())
			failures.add(EligibilityFailure.IMPROVEMENT_PROMOTION_TOO_LOW);
		if (regressed.promotionRate() > parameters.maximumRegressionPromotionRate())
			failures.add(EligibilityFailure.REGRESSION_PROMOTION_TOO_HIGH);
		if (readyAcceptanceRate < parameters.minimumReadyAcceptanceRate())
			failures.add(EligibilityFailure.READY_ACCEPTANCE_TOO_LOW);
		if (unreadyAcceptanceRate > parameters.maximumUnreadyAcceptanceRate())
			failures.add(EligibilityFailure.UNREADY_ACCEPTANCE_TOO_HIGH);
		if (!flatPlateauDetected)
			failures.add(EligibilityFailure.FLAT_SEQUENCE_NOT_PLATEAU);
		if (progressRejectedAsPlateau)
			failures.add(EligibilityFailure.MATERIAL_PROGRESS_REJECTED_AS_PLATEAU);
		double expectedEvaluations = improved.averagePairEvaluations() +
			(double) policy.readinessTrials() * parameters.familyCount();
		return new Metrics(policy.identifier(), failures.isEmpty(), failures, improved.promotionRate(),
			regressed.promotionRate(), readyAcceptanceRate, unreadyAcceptanceRate,
			improved.averagePairEvaluations(), regressed.averagePairEvaluations(), expectedEvaluations,
			flatPlateauDetected, progressRejectedAsPlateau);
	}

	/**
	 * Simulates paired comparisons against one challenger compliance rate.
	 *
	 * @param policy the policy
	 * @param parameters the simulation contract
	 * @param challengerCompliance the challenger pass probability
	 * @param seed the deterministic random seed
	 * @return promotion frequency and evaluation count
	 */
	private static PairMetrics paired(DevelopmentPolicy policy, Parameters parameters, double challengerCompliance,
		long seed)
	{
		SplittableRandom random = new SplittableRandom(seed);
		int promotions = 0;
		long pairEvaluations = 0;
		for (int repetition = 0; repetition < parameters.repetitions(); ++repetition)
		{
			int[] wins = new int[parameters.familyCount()];
			int[] losses = new int[parameters.familyCount()];
			int[] ties = new int[parameters.familyCount()];
			while (true)
			{
				DevelopmentPolicy.ComparisonDecision decision = policy.compare(pairs(wins, losses, ties));
				if (decision.action() != DevelopmentPolicy.ComparisonAction.MORE_PAIRS)
				{
					if (decision.action() == DevelopmentPolicy.ComparisonAction.PROMOTE)
						++promotions;
					break;
				}
				for (int family = 0; family < parameters.familyCount(); ++family)
				{
					for (int pair = 0; pair < decision.additionalPairs(); ++pair)
					{
						boolean championPassed = random.nextDouble() < parameters.championCompliance();
						boolean challengerPassed = random.nextDouble() < challengerCompliance;
						if (challengerPassed && !championPassed)
							++wins[family];
						else if (championPassed && !challengerPassed)
							++losses[family];
						else
							++ties[family];
						++pairEvaluations;
					}
				}
			}
		}
		return new PairMetrics((double) promotions / parameters.repetitions(),
			(double) pairEvaluations / parameters.repetitions());
	}

	/**
	 * Simulates fixed fresh readiness batches.
	 *
	 * @param policy the policy
	 * @param parameters the simulation contract
	 * @param compliance the candidate pass probability
	 * @param seed the deterministic random seed
	 * @return the formal-readiness acceptance rate
	 */
	private static double readiness(DevelopmentPolicy policy, Parameters parameters, double compliance, long seed)
	{
		SplittableRandom random = new SplittableRandom(seed);
		int accepted = 0;
		for (int repetition = 0; repetition < parameters.repetitions(); ++repetition)
		{
			List<DevelopmentPolicy.FamilyReadiness> families = new ArrayList<>();
			for (int family = 0; family < parameters.familyCount(); ++family)
			{
				int passing = 0;
				for (int trial = 0; trial < policy.readinessTrials(); ++trial)
				{
					if (random.nextDouble() < compliance)
						++passing;
				}
				families.add(new DevelopmentPolicy.FamilyReadiness("family-" + family, passing,
					policy.readinessTrials()));
			}
			if (policy.readiness(families).action() == DevelopmentPolicy.ReadinessAction.START_FORMAL_BATCH)
				++accepted;
		}
		return (double) accepted / parameters.repetitions();
	}

	/**
	 * Projects mutable paired counters into immutable family values.
	 *
	 * @param wins challenger wins
	 * @param losses challenger losses
	 * @param ties equal outcomes
	 * @return the family values
	 */
	private static List<DevelopmentPolicy.FamilyPairs> pairs(int[] wins, int[] losses, int[] ties)
	{
		List<DevelopmentPolicy.FamilyPairs> result = new ArrayList<>(wins.length);
		for (int family = 0; family < wins.length; ++family)
			result.add(new DevelopmentPolicy.FamilyPairs("family-" + family, wins[family], losses[family], ties[family]));
		return result;
	}

	/**
	 * Creates a flat score sequence long enough to exercise the policy window.
	 *
	 * @param policy the policy
	 * @return the flat sequence
	 */
	private static List<Double> flatScores(DevelopmentPolicy policy)
	{
		return java.util.Collections.nCopies(policy.plateauRounds() + 1, 0.5);
	}

	/**
	 * Creates a score sequence whose total improvement reaches the policy boundary.
	 *
	 * @param policy the policy
	 * @return the materially improving sequence
	 */
	private static List<Double> progressScores(DevelopmentPolicy policy)
	{
		List<Double> result = new ArrayList<>();
		double improvement = policy.plateauMinimumImprovement();
		for (int index = 0; index <= policy.plateauRounds(); ++index)
			result.add(improvement * index / policy.plateauRounds());
		return result;
	}

	/**
	 * Locates one policy's metrics.
	 *
	 * @param metrics all metrics
	 * @param policy the policy
	 * @return the matching metrics
	 */
	private static Metrics metric(List<Metrics> metrics, DevelopmentPolicy policy)
	{
		return metrics.stream().filter(value -> value.policyIdentifier().equals(policy.identifier())).findFirst().
			orElseThrow();
	}

	/**
	 * Names whether simulation selected a policy.
	 */
	public enum Status
	{
		SELECTED,
		BLOCKED
	}

	/**
	 * Names one unmet simulation selection constraint.
	 */
	public enum EligibilityFailure
	{
		IMPROVEMENT_PROMOTION_TOO_LOW,
		REGRESSION_PROMOTION_TOO_HIGH,
		READY_ACCEPTANCE_TOO_LOW,
		UNREADY_ACCEPTANCE_TOO_HIGH,
		FLAT_SEQUENCE_NOT_PLATEAU,
		MATERIAL_PROGRESS_REJECTED_AS_PLATEAU
	}

	/**
	 * Holds one immutable simulation contract.
	 *
	 * @param repetitions independent repetitions per policy and calibration case
	 * @param familyCount synthetic leaf-family count
	 * @param championCompliance champion pass probability
	 * @param improvedCompliance improved challenger pass probability
	 * @param regressedCompliance regressed challenger pass probability
	 * @param readyCompliance ready candidate pass probability
	 * @param unreadyCompliance unready candidate pass probability
	 * @param minimumImprovementPromotionRate required promotion rate for improved challengers
	 * @param maximumRegressionPromotionRate permitted promotion rate for regressed challengers
	 * @param minimumReadyAcceptanceRate required readiness acceptance rate for ready candidates
	 * @param maximumUnreadyAcceptanceRate permitted readiness acceptance rate for unready candidates
	 * @param seed deterministic random seed
	 */
	public record Parameters(int repetitions, int familyCount, double championCompliance, double improvedCompliance,
		double regressedCompliance, double readyCompliance, double unreadyCompliance,
		double minimumImprovementPromotionRate, double maximumRegressionPromotionRate,
		double minimumReadyAcceptanceRate, double maximumUnreadyAcceptanceRate, long seed)
	{
		/**
		 * Validates one complete simulation contract.
		 *
		 * @param repetitions independent repetitions per policy and calibration case
		 * @param familyCount synthetic leaf-family count
		 * @param championCompliance champion pass probability
		 * @param improvedCompliance improved challenger pass probability
		 * @param regressedCompliance regressed challenger pass probability
		 * @param readyCompliance ready candidate pass probability
		 * @param unreadyCompliance unready candidate pass probability
		 * @param minimumImprovementPromotionRate required promotion rate for improved challengers
		 * @param maximumRegressionPromotionRate permitted promotion rate for regressed challengers
		 * @param minimumReadyAcceptanceRate required readiness acceptance rate for ready candidates
		 * @param maximumUnreadyAcceptanceRate permitted readiness acceptance rate for unready candidates
		 * @param seed deterministic random seed
		 */
		public Parameters
		{
			if (repetitions < 1)
				throw new IllegalArgumentException("repetitions must be positive");
			if (familyCount < 1)
				throw new IllegalArgumentException("familyCount must be positive");
			probability(championCompliance, "championCompliance");
			probability(improvedCompliance, "improvedCompliance");
			probability(regressedCompliance, "regressedCompliance");
			probability(readyCompliance, "readyCompliance");
			probability(unreadyCompliance, "unreadyCompliance");
			probability(minimumImprovementPromotionRate, "minimumImprovementPromotionRate");
			probability(maximumRegressionPromotionRate, "maximumRegressionPromotionRate");
			probability(minimumReadyAcceptanceRate, "minimumReadyAcceptanceRate");
			probability(maximumUnreadyAcceptanceRate, "maximumUnreadyAcceptanceRate");
			if (improvedCompliance <= championCompliance)
				throw new IllegalArgumentException("improvedCompliance must exceed championCompliance");
			if (regressedCompliance >= championCompliance)
				throw new IllegalArgumentException("regressedCompliance must be below championCompliance");
			if (readyCompliance <= unreadyCompliance)
				throw new IllegalArgumentException("readyCompliance must exceed unreadyCompliance");
		}

		/**
		 * Validates an open-unit-interval probability.
		 *
		 * @param value the value
		 * @param name the parameter name
		 */
		private static void probability(double value, String name)
		{
			if (!Double.isFinite(value) || value <= 0 || value >= 1)
				throw new IllegalArgumentException(name + " must be between zero and one");
		}
	}

	/**
	 * Holds every observed calibration metric for one policy.
	 *
	 * @param policyIdentifier the policy identifier
	 * @param eligible whether every selection constraint passed
	 * @param eligibilityFailures unmet selection constraints
	 * @param improvementPromotionRate improved-candidate promotion rate
	 * @param regressionPromotionRate regressed-candidate promotion rate
	 * @param readyAcceptanceRate ready-candidate readiness rate
	 * @param unreadyAcceptanceRate unready-candidate readiness rate
	 * @param averageImprovementPairEvaluations mean pair evaluations for improved candidates
	 * @param averageRegressionPairEvaluations mean pair evaluations for regressed candidates
	 * @param expectedEvaluations optimization and one readiness batch's expected evaluation count
	 * @param flatPlateauDetected whether the flat control produced plateau
	 * @param materialProgressRejectedAsPlateau whether the progress control incorrectly produced plateau
	 */
	public record Metrics(String policyIdentifier, boolean eligible, List<EligibilityFailure> eligibilityFailures,
		double improvementPromotionRate, double regressionPromotionRate, double readyAcceptanceRate,
		double unreadyAcceptanceRate, double averageImprovementPairEvaluations,
		double averageRegressionPairEvaluations, double expectedEvaluations, boolean flatPlateauDetected,
		boolean materialProgressRejectedAsPlateau)
	{
		/**
		 * Validates and snapshots one policy result.
		 *
		 * @param policyIdentifier the policy identifier
		 * @param eligible whether every selection constraint passed
		 * @param eligibilityFailures unmet selection constraints
		 * @param improvementPromotionRate improved-candidate promotion rate
		 * @param regressionPromotionRate regressed-candidate promotion rate
		 * @param readyAcceptanceRate ready-candidate readiness rate
		 * @param unreadyAcceptanceRate unready-candidate readiness rate
		 * @param averageImprovementPairEvaluations mean pair evaluations for improved candidates
		 * @param averageRegressionPairEvaluations mean pair evaluations for regressed candidates
		 * @param expectedEvaluations optimization and one readiness batch's expected evaluation count
		 * @param flatPlateauDetected whether the flat control produced plateau
		 * @param materialProgressRejectedAsPlateau whether the progress control incorrectly produced plateau
		 */
		public Metrics
		{
			policyIdentifier = requireText(policyIdentifier, "policyIdentifier");
			eligibilityFailures = List.copyOf(eligibilityFailures);
			if (eligible == !eligibilityFailures.isEmpty())
				throw new IllegalArgumentException("Only an ineligible policy must name eligibility failures");
		}
	}

	/**
	 * Holds a selected policy or the evidence that all proposals were blocked.
	 *
	 * @param status the selection result
	 * @param selectedPolicy the selected policy
	 * @param metrics every candidate's metrics
	 * @param parameters the exact simulation contract
	 */
	public record Result(Status status, Optional<DevelopmentPolicy> selectedPolicy, List<Metrics> metrics,
		Parameters parameters)
	{
		/**
		 * Validates and snapshots one selection result.
		 *
		 * @param status the selection result
		 * @param selectedPolicy the selected policy
		 * @param metrics every candidate's metrics
		 * @param parameters the exact simulation contract
		 */
		public Result
		{
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(selectedPolicy, "selectedPolicy must not be null");
			metrics = List.copyOf(metrics);
			Objects.requireNonNull(parameters, "parameters must not be null");
			if ((status == Status.SELECTED) != selectedPolicy.isPresent())
				throw new IllegalArgumentException("Only a selected result must contain a policy");
		}
	}

	/**
	 * Holds one paired calibration result.
	 *
	 * @param promotionRate terminal promotion frequency
	 * @param averagePairEvaluations mean evaluated candidate-version pairs
	 */
	private record PairMetrics(double promotionRate, double averagePairEvaluations)
	{
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
}
