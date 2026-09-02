package io.github.cowwoc.cat.common.optimization.certification;


import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Selects one proof-covered formal action from current leaf-family evidence.
 */
public final class FamilySequentialDecision
{
	/**
	 * Prevents construction of this static policy owner.
	 */
	private FamilySequentialDecision()
	{
	}

	/**
	 * Selects the next formal action without requiring equal trial counts across families.
	 *
	 * @param evidence every leaf family's completed formal outcomes
	 * @param assignment the candidate allowance assigned before formal outcomes existed
	 * @param policy the frozen certification target and trial cap
	 * @return the certification projection and its only permitted next action
	 */
	public static Result evaluate(List<FamilyCertification.Evidence> evidence,
		CandidateAllowanceLedger.Assignment assignment, Policy policy)
	{
		if (evidence == null)
			throw new NullPointerException("evidence must not be null");
		if (assignment == null)
			throw new NullPointerException("assignment must not be null");
		if (policy == null)
			throw new NullPointerException("policy must not be null");
		if (evidence.isEmpty())
			throw new IllegalArgumentException("evidence must contain every defined leaf family");

		double candidateAllowance = assignment.allowance().doubleValue();
		FamilyCertification.Result certification = FamilyCertification.evaluate(evidence, candidateAllowance,
			policy.target());
		if (certification.status() == FamilyCertification.Status.CERTIFIED)
			return new Result(Action.CERTIFIED, Optional.empty(), certification);

		Optional<FamilyCertification.Evidence> futile = evidence.stream().
			filter(family -> certificationUnreachable(family, candidateAllowance, policy)).
			min(Comparator.comparing(FamilyCertification.Evidence::familyId));
		if (futile.isPresent())
			return new Result(Action.FUTILE, Optional.of(futile.orElseThrow().familyId()), certification);

		FamilyCertification.Evidence nextFamily = evidence.stream().
			filter(family -> certification.familyBounds().get(family.familyId()) <= policy.target()).
			min(Comparator.comparingDouble((FamilyCertification.Evidence family) ->
				certification.familyBounds().get(family.familyId())).
				thenComparingInt(FamilyCertification.Evidence::completeTrials).
				thenComparing(FamilyCertification.Evidence::familyId)).orElseThrow();
		return new Result(Action.SAMPLE_FAMILY, Optional.of(nextFamily.familyId()), certification);
	}

	/**
	 * Determines whether perfect remaining outcomes still cannot cross the target.
	 *
	 * @param evidence one family's completed outcomes
	 * @param candidateAllowance the candidate's false-certification allowance
	 * @param policy the frozen certification policy
	 * @return whether the family cannot certify within the cap
	 */
	private static boolean certificationUnreachable(FamilyCertification.Evidence evidence, double candidateAllowance,
		Policy policy)
	{
		if (evidence.completeTrials() > policy.maximumTrialsPerFamily())
		{
			throw new IllegalArgumentException("Family " + evidence.familyId() +
				" exceeds maximumTrialsPerFamily: " + evidence.completeTrials());
		}
		int remainingTrials = policy.maximumTrialsPerFamily() - evidence.completeTrials();
		double bestPossibleBound = Statistics.sequentialLowerConfidenceBound(evidence.passingTrials() + remainingTrials,
			policy.maximumTrialsPerFamily(), candidateAllowance);
		return bestPossibleBound <= policy.target();
	}

	/**
	 * Names the formal engine's next action.
	 */
	public enum Action
	{
		SAMPLE_FAMILY,
		CERTIFIED,
		FUTILE
	}

	/**
	 * Holds the frozen formal certification policy.
	 *
	 * @param target the lower bound every family must exceed
	 * @param maximumTrialsPerFamily the hard formal-trial cap for one family
	 */
	public record Policy(double target, int maximumTrialsPerFamily)
	{
		/**
		 * Validates one formal policy.
		 *
		 * @param target the lower bound every family must exceed
		 * @param maximumTrialsPerFamily the hard formal-trial cap for one family
		 */
		public Policy
		{
			if (target <= 0 || target >= 1)
				throw new IllegalArgumentException("target must be between zero and one");
			if (maximumTrialsPerFamily < 1)
				throw new IllegalArgumentException("maximumTrialsPerFamily must be positive");
		}
	}

	/**
	 * Holds the certification projection and next formal action.
	 *
	 * @param action the only permitted next formal action
	 * @param familyId the family to sample or the family that makes certification futile
	 * @param certification the current weakest-family projection
	 */
	public record Result(Action action, Optional<String> familyId, FamilyCertification.Result certification)
	{
		/**
		 * Validates one decision result.
		 *
		 * @param action the only permitted next formal action
		 * @param familyId the family to sample or the family that makes certification futile
		 * @param certification the current weakest-family projection
		 */
		public Result
		{
			if (action == null)
				throw new NullPointerException("action must not be null");
			if (familyId == null)
				throw new NullPointerException("familyId must not be null");
			if (certification == null)
				throw new NullPointerException("certification must not be null");
			boolean familyRequired = action == Action.SAMPLE_FAMILY || action == Action.FUTILE;
			if (familyRequired != familyId.isPresent())
				throw new IllegalArgumentException("familyId presence does not match action: " + action);
		}
	}
}
