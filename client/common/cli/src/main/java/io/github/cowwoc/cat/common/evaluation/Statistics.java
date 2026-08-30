package io.github.cowwoc.cat.common.evaluation;

/**
 * Computes prompt-evaluation compliance decisions from completed whole-prompt trial outcomes.
 */
public final class Statistics
{
	/**
	 * Prevents construction of this static policy component.
	 */
	private Statistics()
	{
	}

	/**
	 * Computes the decision that the completed outcomes establish.
	 *
	 * @param parameters the fixed certification boundaries
	 * @param completeTrials the number of completed whole-prompt trials
	 * @param passingTrials the number of completed whole-prompt trials that passed every testcase
	 * @param infrastructureFailure whether the run has invalid execution evidence
	 * @param routingFailure whether routing evidence shows the executor missed a required rule
	 * @return the complete statistical decision
	 */
	public static Result evaluate(Parameters parameters, int completeTrials, int passingTrials,
		boolean infrastructureFailure,
		boolean routingFailure)
	{
		if (parameters == null)
			throw new NullPointerException("parameters must not be null");
		if (completeTrials < 0 || completeTrials > parameters.maximumTrials())
		{
			throw new IllegalArgumentException("completeTrials must be between zero and maximumTrials: " +
				completeTrials);
		}
		if (passingTrials < 0 || passingTrials > completeTrials)
			throw new IllegalArgumentException("passingTrials must be between zero and completeTrials: " + passingTrials);

		double[] logFactorials = logFactorials(parameters.maximumTrials() + 1);
		int failedTrials = completeTrials - passingTrials;
		double likelihood = passingTrials * Math.log(parameters.acceptableRate() / parameters.unacceptableRate()) +
			failedTrials * Math.log((1 - parameters.acceptableRate()) / (1 - parameters.unacceptableRate()));
		double acceptBoundary = Math.log((1 - parameters.falseRejectRate()) / parameters.falseAcceptRate());
		double rejectBoundary = Math.log(parameters.falseRejectRate() / (1 - parameters.falseAcceptRate()));
		SprtDecision sprtDecision = sprtDecision(likelihood, acceptBoundary, rejectBoundary);
		double lowerConfidenceBound = sequentialLowerConfidenceBound(passingTrials, completeTrials,
			parameters.falseAcceptRate(), logFactorials);
		boolean certificationUnreachable = certificationUnreachable(parameters, passingTrials, completeTrials,
			logFactorials);
		OverallDecision overallDecision;
		if (infrastructureFailure)
			overallDecision = OverallDecision.INFRASTRUCTURE_FAILED;
		else if (routingFailure)
			overallDecision = OverallDecision.FAIL;
		else if (lowerConfidenceBound >= parameters.requiredCompliance())
			overallDecision = OverallDecision.PASS;
		else if (sprtDecision == SprtDecision.REJECT || certificationUnreachable)
			overallDecision = OverallDecision.FAIL;
		else if (completeTrials >= parameters.maximumTrials())
			overallDecision = OverallDecision.INCONCLUSIVE;
		else
			overallDecision = OverallDecision.RUNNING;
		return new Result(overallDecision, completeTrials, passingTrials, rate(passingTrials, failedTrials),
			lowerConfidenceBound, sprtDecision, certificationUnreachable, likelihood);
	}

	/**
	 * Calculates the completed-trial pass rate.
	 *
	 * @param passed the passed-trial count
	 * @param failed the failed-trial count
	 * @return the pass rate, or zero before a completed trial
	 */
	public static double rate(int passed, int failed)
	{
		if (passed < 0 || failed < 0)
			throw new IllegalArgumentException("Trial counts must not be negative");
		if (passed + failed == 0)
			return 0;
		return (double) passed / (passed + failed);
	}

	/**
	 * Determines whether perfect remaining trials cannot meet the certification confidence boundary.
	 *
	 * @param parameters the fixed certification boundaries
	 * @param passingTrials the completed passing trials
	 * @param completeTrials the completed trials
	 * @param logFactorials the precomputed factorial logarithms through the trial cap
	 * @return whether certification is impossible within the trial cap
	 */
	private static boolean certificationUnreachable(Parameters parameters, int passingTrials, int completeTrials,
		double[] logFactorials)
	{
		int remainingTrials = parameters.maximumTrials() - completeTrials;
		return sequentialLowerConfidenceBound(passingTrials + remainingTrials, parameters.maximumTrials(),
			parameters.falseAcceptRate(), logFactorials) < parameters.requiredCompliance();
	}

	/**
	 * Selects the sequential probability-ratio state.
	 *
	 * @param likelihood the accumulated likelihood ratio
	 * @param acceptBoundary the acceptance boundary
	 * @param rejectBoundary the rejection boundary
	 * @return the statistical decision
	 */
	private static SprtDecision sprtDecision(double likelihood, double acceptBoundary, double rejectBoundary)
	{
		if (likelihood >= acceptBoundary)
			return SprtDecision.ACCEPT;
		if (likelihood <= rejectBoundary)
			return SprtDecision.REJECT;
		return SprtDecision.RUNNING;
	}

	/**
	 * Returns an anytime-valid lower confidence bound for whole-prompt compliance.
	 *
	 * @param passed the number of complete-suite passes
	 * @param total the number of complete-suite outcomes
	 * @param falseAcceptRate the permitted false-certification probability
	 * @param logFactorials the precomputed factorial logarithms through the trial cap
	 * @return the largest compliance rate excluded from the low-compliance side
	 */
	private static double sequentialLowerConfidenceBound(int passed, int total, double falseAcceptRate,
		double[] logFactorials)
	{
		if (passed == 0 || total == 0)
			return 0;
		double threshold = Math.log(1 / falseAcceptRate);
		double lower = 0;
		double upper = 1 - 1e-12;
		for (int iteration = 0; iteration < 80; ++iteration)
		{
			double candidate = (lower + upper) / 2;
			if (logUniformMixtureEvidence(passed, total, candidate, logFactorials) >= threshold)
				lower = candidate;
			else
				upper = candidate;
		}
		return lower;
	}

	/**
	 * Computes mixture evidence against one proposed low-compliance boundary.
	 *
	 * @param passed the completed passing trial count
	 * @param total the completed trial count
	 * @param boundary the proposed low-compliance boundary
	 * @param logFactorials the precomputed factorial logarithms through the trial cap
	 * @return the natural logarithm of the mixture evidence
	 */
	private static double logUniformMixtureEvidence(int passed, int total, double boundary, double[] logFactorials)
	{
		if (boundary <= 0)
			return Double.POSITIVE_INFINITY;
		if (boundary >= 1)
			return Double.NEGATIVE_INFINITY;
		int failures = total - passed;
		int binomialTrials = total + 1;
		double logBeta = logFactorials[passed] + logFactorials[failures] - logFactorials[binomialTrials];
		double logTail = logBinomialCdf(binomialTrials, passed, boundary, logFactorials);
		return logBeta + logTail - Math.log1p(-boundary) - passed * Math.log(boundary) -
			failures * Math.log1p(-boundary);
	}

	/**
	 * Computes the natural logarithm of a binomial lower-tail probability.
	 *
	 * @param trials the number of Bernoulli trials
	 * @param maximumSuccesses the inclusive upper success count
	 * @param probability the success probability
	 * @param logFactorials the precomputed factorial logarithms through the trial cap
	 * @return the logarithm of {@code P(X <= maximumSuccesses)}
	 */
	private static double logBinomialCdf(int trials, int maximumSuccesses, double probability, double[] logFactorials)
	{
		double maximum = Double.NEGATIVE_INFINITY;
		for (int successes = 0; successes <= maximumSuccesses; ++successes)
			maximum = Math.max(maximum, logBinomialProbability(trials, successes, probability, logFactorials));
		double sum = 0;
		for (int successes = 0; successes <= maximumSuccesses; ++successes)
			sum += Math.exp(logBinomialProbability(trials, successes, probability, logFactorials) - maximum);
		return maximum + Math.log(sum);
	}

	/**
	 * Computes the natural logarithm of one binomial probability.
	 *
	 * @param trials the number of Bernoulli trials
	 * @param successes the success count
	 * @param probability the success probability
	 * @param logFactorials the precomputed factorial logarithms through the trial cap
	 * @return the log probability
	 */
	private static double logBinomialProbability(int trials, int successes, double probability, double[] logFactorials)
	{
		return logFactorials[trials] - logFactorials[successes] - logFactorials[trials - successes] +
			successes * Math.log(probability) + (trials - successes) * Math.log1p(-probability);
	}

	/**
	 * Computes the natural logarithms of every nonnegative factorial through one inclusive maximum.
	 *
	 * @param maximum the largest factorial operand
	 * @return the logarithm of each factorial, indexed by its operand
	 */
	private static double[] logFactorials(int maximum)
	{
		double[] result = new double[maximum + 1];
		for (int factor = 2; factor <= maximum; ++factor)
			result[factor] = result[factor - 1] + Math.log(factor);
		return result;
	}

	/**
	 * Holds the fixed statistical boundaries for one certification suite.
	 *
	 * @param requiredCompliance the lower confidence bound required to certify
	 * @param acceptableRate the rate SPRT accepts
	 * @param unacceptableRate the rate SPRT rejects
	 * @param falseAcceptRate the permitted false-certification probability
	 * @param falseRejectRate the permitted false-rejection probability
	 * @param maximumTrials the maximum complete whole-prompt trials
	 */
	public record Parameters(double requiredCompliance, double acceptableRate, double unacceptableRate,
		double falseAcceptRate,
		double falseRejectRate, int maximumTrials)
	{
		/**
		 * Validates one fixed certification policy.
		 *
		 * @param requiredCompliance the lower confidence bound required to certify
		 * @param acceptableRate the rate SPRT accepts
		 * @param unacceptableRate the rate SPRT rejects
		 * @param falseAcceptRate the permitted false-certification probability
		 * @param falseRejectRate the permitted false-rejection probability
		 * @param maximumTrials the maximum complete whole-prompt trials
		 */
		public Parameters
		{
			if (requiredCompliance <= 0 || requiredCompliance >= 1)
				throw new IllegalArgumentException("requiredCompliance must be between zero and one");
			if (acceptableRate <= 0 || acceptableRate >= 1)
				throw new IllegalArgumentException("acceptableRate must be between zero and one");
			if (unacceptableRate <= 0 || unacceptableRate >= acceptableRate)
				throw new IllegalArgumentException("unacceptableRate must be between zero and acceptableRate");
			if (falseAcceptRate <= 0 || falseAcceptRate >= 1)
				throw new IllegalArgumentException("falseAcceptRate must be between zero and one");
			if (falseRejectRate <= 0 || falseRejectRate >= 1)
				throw new IllegalArgumentException("falseRejectRate must be between zero and one");
			if (maximumTrials < 1)
				throw new IllegalArgumentException("maximumTrials must be positive");
		}
	}

	/**
	 * Names the externally reported overall evaluation result.
	 */
	public enum OverallDecision
	{
		PASS,
		FAIL,
		INCONCLUSIVE,
		RUNNING,
		INFRASTRUCTURE_FAILED
	}

	/**
	 * Names the sequential probability-ratio result.
	 */
	public enum SprtDecision
	{
		ACCEPT,
		REJECT,
		RUNNING
	}

	/**
	 * Holds the complete policy result for one observed whole-prompt trial set.
	 *
	 * @param overallDecision the terminal or continuing result
	 * @param completeTrials the number of completed whole-prompt trials
	 * @param passingTrials the number of completed trials that passed every testcase
	 * @param observedCompliance the completed-trial pass rate
	 * @param lowerConfidenceBound the anytime-valid 95% lower compliance bound
	 * @param sprtDecision the SPRT evidence state
	 * @param certificationUnreachable whether the trial cap makes certification impossible
	 * @param sprtLogLikelihoodRatio the accumulated SPRT likelihood ratio
	 */
	public record Result(OverallDecision overallDecision, int completeTrials, int passingTrials,
		double observedCompliance,
		double lowerConfidenceBound, SprtDecision sprtDecision, boolean certificationUnreachable,
		double sprtLogLikelihoodRatio)
	{
	}
}
