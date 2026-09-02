package io.github.cowwoc.cat.common.optimization.certification;


import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Calculates one simultaneous weakest-family certification result without weighting substantive scenario families.
 */
public final class FamilyCertification
{
	/**
	 * Prevents construction of this static policy component.
	 */
	private FamilyCertification()
	{
	}

	/**
	 * Calculates the current lower bound across every defined leaf scenario family.
	 *
	 * <p>Every family receives the candidate's full false-certification allowance. Certification requires every family
	 * lower bound to exceed the target, so a false certification implies that at least one weak family crossed its bound
	 * using that same allowance.</p>
	 *
	 * @param evidence the completed outcomes for every defined leaf scenario family
	 * @param candidateAllowance the candidate's permitted false-certification probability
	 * @param target the compliance lower bound that every family must exceed
	 * @return the simultaneous weakest-family result
	 */
	public static Result evaluate(List<Evidence> evidence, double candidateAllowance, double target)
	{
		if (evidence == null)
			throw new NullPointerException("evidence must not be null");
		if (candidateAllowance <= 0 || candidateAllowance >= 1)
			throw new IllegalArgumentException("candidateAllowance must be between zero and one");
		if (target <= 0 || target >= 1)
			throw new IllegalArgumentException("target must be between zero and one");
		if (evidence.isEmpty())
			return new Result(Status.EVIDENCE_UNAVAILABLE, Double.NaN, Map.of());

		Map<String, Double> sortedBounds = new TreeMap<>();
		double currentLowerBound = 1;
		int completeTrials = 0;
		for (Evidence familyEvidence : evidence)
		{
			if (familyEvidence == null)
				throw new NullPointerException("evidence must not contain null elements");
			double familyBound = Statistics.sequentialLowerConfidenceBound(familyEvidence.passingTrials(),
				familyEvidence.completeTrials(), candidateAllowance);
			if (sortedBounds.putIfAbsent(familyEvidence.familyId(), familyBound) != null)
				throw new IllegalArgumentException("Duplicate family identifier: " + familyEvidence.familyId());
			currentLowerBound = Math.min(currentLowerBound, familyBound);
			completeTrials += familyEvidence.completeTrials();
		}

		Status status;
		if (completeTrials == 0)
		{
			status = Status.EVIDENCE_UNAVAILABLE;
			currentLowerBound = Double.NaN;
		}
		else if (currentLowerBound > target)
			status = Status.CERTIFIED;
		else
			status = Status.NOT_CERTIFIED;
		Map<String, Double> familyBounds = Collections.unmodifiableMap(new LinkedHashMap<>(sortedBounds));
		return new Result(status, currentLowerBound, familyBounds);
	}

	/**
	 * Holds the complete outcomes for one leaf scenario family.
	 *
	 * @param familyId the stable certification-contract family identifier
	 * @param passingTrials the number of passing outcomes
	 * @param completeTrials the number of completed outcomes
	 */
	public record Evidence(String familyId, int passingTrials, int completeTrials)
	{
		/**
		 * Validates one family's evidence.
		 *
		 * @param familyId the stable certification-contract family identifier
		 * @param passingTrials the number of passing outcomes
		 * @param completeTrials the number of completed outcomes
		 */
		public Evidence
		{
			if (familyId == null)
				throw new NullPointerException("familyId must not be null");
			if (familyId.isBlank())
				throw new IllegalArgumentException("familyId must not be blank");
			if (completeTrials < 0)
				throw new IllegalArgumentException("completeTrials must not be negative: " + completeTrials);
			if (passingTrials < 0 || passingTrials > completeTrials)
			{
				throw new IllegalArgumentException("passingTrials must be between zero and completeTrials: " +
					passingTrials);
			}
		}
	}

	/**
	 * Names whether the available family evidence certifies the candidate.
	 */
	public enum Status
	{
		CERTIFIED,
		NOT_CERTIFIED,
		EVIDENCE_UNAVAILABLE
	}

	/**
	 * Holds the simultaneous weakest-family certification result.
	 *
	 * @param status whether the evidence certifies the candidate
	 * @param currentLowerBound the smallest family lower bound, or {@link Double#NaN} when evidence is unavailable
	 * @param familyBounds each family lower bound, ordered by family identifier
	 */
	public record Result(Status status, double currentLowerBound, Map<String, Double> familyBounds)
	{
		/**
		 * Validates and snapshots a certification result.
		 *
		 * @param status whether the evidence certifies the candidate
		 * @param currentLowerBound the smallest family lower bound, or {@link Double#NaN} when evidence is unavailable
		 * @param familyBounds each family lower bound, ordered by family identifier
		 */
		public Result
		{
			if (status == null)
				throw new NullPointerException("status must not be null");
			if (familyBounds == null)
				throw new NullPointerException("familyBounds must not be null");
			familyBounds = Collections.unmodifiableMap(new LinkedHashMap<>(familyBounds));
		}
	}
}
