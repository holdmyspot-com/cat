package io.github.cowwoc.cat.common.optimization.certification;


import java.math.BigDecimal;
import java.util.SplittableRandom;

/**
 * Simulates repeated formal candidate selection against the family-sequential lineage error-control policy.
 */
public final class CertificationCoverageSimulation
{
	/**
	 * Prevents construction of this static simulation owner.
	 */
	private CertificationCoverageSimulation()
	{
	}

	/**
	 * Runs a deterministic adversarial simulation using precomputed crossing boundaries.
	 *
	 * @param parameters the fixed simulation manifest
	 * @return the observed false-certification result and its one-sided uncertainty bound
	 */
	public static Result run(Parameters parameters)
	{
		if (parameters == null)
			throw new NullPointerException("parameters must not be null");
		BigDecimal totalBudget = BigDecimal.valueOf(parameters.lineageBudget());
		double[] allowances = new double[parameters.candidateCount()];
		int[][] minimumPasses = new int[parameters.candidateCount()][parameters.maximumTrials() + 1];
		double assignedAllowance = 0;
		for (int candidate = 0; candidate < parameters.candidateCount(); ++candidate)
		{
			allowances[candidate] = CandidateAllowanceLedger.Schedule.HALVING_V1.
				allowance(totalBudget, candidate + 1).doubleValue();
			assignedAllowance += allowances[candidate];
			for (int trial = 1; trial <= parameters.maximumTrials(); ++trial)
			{
				minimumPasses[candidate][trial] = Statistics.minimumPassingTrials(trial, allowances[candidate],
					parameters.target());
			}
		}

		SplittableRandom random = new SplittableRandom(parameters.seed());
		int falseCertifications = 0;
		for (int repetition = 0; repetition < parameters.repetitions(); ++repetition)
		{
			if (falseCertification(random, parameters, minimumPasses))
				++falseCertifications;
		}
		double observedRate = (double) falseCertifications / parameters.repetitions();
		double upperRate = Statistics.upperConfidenceBound(falseCertifications, parameters.repetitions(),
			parameters.simulationFalseExclusionRate());
		return new Result(parameters, falseCertifications, observedRate, upperRate, assignedAllowance);
	}

	/**
	 * Simulates one lineage until a candidate falsely certifies or all candidates reach their trial caps.
	 *
	 * @param random the deterministic random source
	 * @param parameters the fixed simulation manifest
	 * @param minimumPasses each candidate and trial count's crossing boundary
	 * @return whether the lineage falsely certified
	 */
	private static boolean falseCertification(SplittableRandom random, Parameters parameters, int[][] minimumPasses)
	{
		for (int candidate = 0; candidate < parameters.candidateCount(); ++candidate)
		{
			int passingTrials = 0;
			for (int trial = 1; trial <= parameters.maximumTrials(); ++trial)
			{
				if (random.nextDouble() < parameters.trueCompliance())
					++passingTrials;
				if (passingTrials >= minimumPasses[candidate][trial])
					return true;
			}
		}
		return false;
	}

	/**
	 * Holds one immutable simulation manifest.
	 *
	 * @param repetitions the number of independently simulated optimization lineages
	 * @param candidateCount the number of adaptively retried candidates per lineage
	 * @param maximumTrials the finite trial cap for each candidate family
	 * @param trueCompliance the adversarial weakest-family compliance rate
	 * @param target the certification boundary
	 * @param lineageBudget the lineage-wide false-certification budget
	 * @param simulationFalseExclusionRate the one-sided uncertainty allowance for the simulated error rate
	 * @param seed the deterministic random seed
	 */
	public record Parameters(int repetitions, int candidateCount, int maximumTrials, double trueCompliance,
		double target, double lineageBudget, double simulationFalseExclusionRate, long seed)
	{
		/**
		 * Validates one simulation manifest.
		 *
		 * @param repetitions the number of independently simulated optimization lineages
		 * @param candidateCount the number of adaptively retried candidates per lineage
		 * @param maximumTrials the finite trial cap for each candidate family
		 * @param trueCompliance the adversarial weakest-family compliance rate
		 * @param target the certification boundary
		 * @param lineageBudget the lineage-wide false-certification budget
		 * @param simulationFalseExclusionRate the one-sided uncertainty allowance for the simulated error rate
		 * @param seed the deterministic random seed
		 */
		public Parameters
		{
			if (repetitions < 1)
				throw new IllegalArgumentException("repetitions must be positive");
			if (candidateCount < 1)
				throw new IllegalArgumentException("candidateCount must be positive");
			if (maximumTrials < 1)
				throw new IllegalArgumentException("maximumTrials must be positive");
			if (trueCompliance < 0 || trueCompliance > target)
				throw new IllegalArgumentException("trueCompliance must be between zero and target");
			if (target <= 0 || target >= 1)
				throw new IllegalArgumentException("target must be between zero and one");
			if (lineageBudget <= 0 || lineageBudget > 0.05)
				throw new IllegalArgumentException("lineageBudget must be greater than zero and no more than 0.05");
			if (simulationFalseExclusionRate <= 0 || simulationFalseExclusionRate >= 1)
			{
				throw new IllegalArgumentException("simulationFalseExclusionRate must be between zero and one");
			}
		}
	}

	/**
	 * Holds one complete simulation result.
	 *
	 * @param parameters the exact simulation manifest
	 * @param falseCertifications the lineages that issued a certificate at or below the target
	 * @param observedFalseCertificationRate the observed false-certification proportion
	 * @param oneSidedUpperFalseCertificationRate the exact fixed-sample one-sided upper error bound
	 * @param assignedAllowance the probability allowance assigned across simulated candidates
	 */
	public record Result(Parameters parameters, int falseCertifications, double observedFalseCertificationRate,
		double oneSidedUpperFalseCertificationRate, double assignedAllowance)
	{
	}
}
