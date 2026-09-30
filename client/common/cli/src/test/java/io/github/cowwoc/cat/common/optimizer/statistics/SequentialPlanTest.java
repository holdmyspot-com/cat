package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests sequential certification planning and decisions.
 */
public final class SequentialPlanTest
{
	/**
	 * Verifies the first campaign attempts reach the stated power with the smallest cap.
	 */
	@Test
	public void calibratesDefaultAttemptCaps()
	{
		for (int attempt = 1; attempt <= 3; ++attempt)
		{
			SequentialPlan plan = SequentialPlan.forPower(LineageAllowance.forCandidate(attempt),
				probability(19, 20), probability(99, 100), probability(9, 10));
			int expectedCap = switch (attempt)
			{
				case 1 -> 250;
				case 2 -> 317;
				default -> 334;
			};
			requireThat(plan.cap(), "cap").isEqualTo(expectedCap);
			requireThat(plan.achievedPower().compareTo(probability(9, 10)), "achievedPower").
				isGreaterThanOrEqualTo(0);
		}
	}

	/**
	 * Verifies a valid planning rate close to the threshold is not rejected by a fixed search ceiling.
	 */
	@Test
	public void calibratesBeyondTenThousandTrials()
	{
		SequentialPlan plan = SequentialPlan.forPower(LineageAllowance.forCandidate(1),
			probability(19, 20), probability(957, 1000), probability(9, 10));
		requireThat(plan.cap(), "cap").isEqualTo(12_934);
		requireThat(plan.achievedPower().compareTo(probability(9, 10)), "achievedPower").
			isGreaterThanOrEqualTo(0);
	}

	/**
	 * Counts certification after an earlier lower or upper stop without resuming those paths.
	 */
	@Test
	public void calibratesExactStoppedPathProbability()
	{
		SequentialPlan plan = SequentialPlan.forPower(probability(1, 2), probability(1, 2),
			probability(3, 4), probability(3, 4));
		// Enumerating all eight-outcome paths gives 25803/32768; seven outcomes give only 369/512.
		requireThat(plan.cap(), "firstCapMeetingPower").isEqualTo(8);
		requireThat(plan.achievedPower(), "exactStoppedPathProbability").isEqualTo(probability(25_803, 32_768));
	}

	/**
	 * The same exact directional counts cross identically when revisiting an earlier look.
	 */
	@Test
	public void retainsEvidenceAcrossCountOrder()
	{
		SequentialEvidence evidence = new SequentialEvidence(probability(1, 2), probability(1, 2),
			probability(3, 4));
		requireThat(evidence.lowerCrosses(2, 0), "twoCompliantOutcomes").isTrue();
		requireThat(evidence.lowerCrosses(1, 0), "earlierCompliantLook").isFalse();
		requireThat(evidence.lowerCrosses(2, 0), "repeatedCompliantLook").isTrue();
		requireThat(evidence.upperCrosses(0, 2), "twoNoncompliantOutcomes").isTrue();
		requireThat(evidence.upperCrosses(0, 1), "earlierNoncompliantLook").isFalse();
		requireThat(evidence.upperCrosses(0, 2), "repeatedNoncompliantLook").isTrue();
	}

	/**
	 * Earlier noncompliant paths stay stopped while later compliant paths reach the calibrated cap.
	 */
	@Test
	public void calibratesPowerWithEarlyUpperStops()
	{
		SequentialPlan plan = SequentialPlan.forPower(probability(1, 4), probability(1, 2),
			probability(3, 4), probability(2, 3));
		// Enumerating the twelve-outcome paths gives 6095817/8388608; eleven give only 84483/131072.
		requireThat(plan.cap(), "firstCapMeetingPower").isEqualTo(12);
		requireThat(plan.achievedPower(), "exactStoppedPathProbability").isEqualTo(
			probability(6_095_817, 8_388_608));
	}

	/**
	 * Verifies a mathematically finite all-pass cap is not rejected at the old search limit.
	 */
	@Test
	public void calibratesLongAllPassSequence()
	{
		RationalProbability smallAllowance = new RationalProbability(BigInteger.ONE,
			BigInteger.TEN.pow(240));
		SequentialPlan plan = SequentialPlan.forPower(smallAllowance, probability(19, 20),
			probability(1, 1), probability(9, 10));
		requireThat(plan.cap(), "cap").isEqualTo(10_774);
		requireThat(plan.achievedPower(), "achievedPower").isEqualTo(probability(1, 1));
	}

	/**
	 * Verifies mass already stopped below the minimum can make the requested power unattainable.
	 */
	@Test(expectedExceptions = IllegalStateException.class,
		expectedExceptionsMessageRegExp = ".*unattainable after below-minimum stops.*", timeOut = 5_000)
	public void rejectsUnattainablePlanningPower()
	{
		SequentialPlan.forPower(probability(9, 10), probability(1, 2),
			probability(51, 100), probability(99, 100));
	}

	/**
	 * Verifies the public attempt planner derives the campaign allowance.
	 */
	@Test
	public void derivesExactCampaignAllowance()
	{
		SequentialPlan plan = SequentialPlan.forAttempt(2, probability(19, 20),
			probability(99, 100), probability(9, 10));
		requireThat(plan.alpha(), "attemptAllowance").isEqualTo(probability(1, 120));
		requireThat(plan.cap(), "attemptCap").isEqualTo(317);
	}

	/**
	 * Verifies a zero attempt number cannot receive a fresh allowance.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsZeroAttemptNumber()
	{
		SequentialPlan.forAttempt(0, probability(19, 20),
			probability(99, 100), probability(9, 10));
	}

	/**
	 * Verifies confirmed decimal planning values retain their exact rational meaning.
	 */
	@Test
	public void convertsConfirmedDecimalExactly()
	{
		requireThat(RationalProbability.fromDecimalExact(new BigDecimal("0.985")), "decimal").
			isEqualTo(probability(197, 200));
	}

	/**
	 * Verifies a decimal outside probability range is excluded before planning.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsInvalidConfirmedDecimal()
	{
		RationalProbability.fromDecimalExact(new BigDecimal("1.001"));
	}

	/**
	 * Verifies repeated looks can certify early, continue, or stop below the minimum.
	 */
	@Test
	public void decidesAtEveryObservedPrefix()
	{
		SequentialPlan plan = SequentialPlan.forPower(LineageAllowance.forCandidate(1),
			probability(19, 20), probability(99, 100), probability(9, 10));
		requireThat(plan.decide(new CategoryCounts(0, 0)), "empty").isEqualTo(SequentialStatus.CONTINUE);
		requireThat(plan.decide(new CategoryCounts(70, 70)), "earlyAllPass").
			isEqualTo(SequentialStatus.CONTINUE);
		requireThat(plan.decide(new CategoryCounts(100, 100)), "earlyCertification").
			isEqualTo(SequentialStatus.CERTIFIED);
		requireThat(plan.decide(new CategoryCounts(0, 10)), "belowMinimum").
			isEqualTo(SequentialStatus.BELOW_MINIMUM);
	}

	/**
	 * Verifies the exact assigned attempt allowance changes an early decision.
	 */
	@Test
	public void usesAttemptAllowanceAtRepeatedLooks()
	{
		SequentialPlan first = SequentialPlan.forPower(LineageAllowance.forCandidate(1),
			probability(19, 20), probability(99, 100), probability(9, 10));
		SequentialPlan second = SequentialPlan.forPower(LineageAllowance.forCandidate(2),
			probability(19, 20), probability(99, 100), probability(9, 10));
		requireThat(first.decide(new CategoryCounts(90, 90)), "firstAttempt").
			isEqualTo(SequentialStatus.CERTIFIED);
		requireThat(second.decide(new CategoryCounts(90, 90)), "secondAttempt").
			isEqualTo(SequentialStatus.CONTINUE);
	}

	/**
	 * Verifies a strict evidence equality does not cross either directional boundary.
	 */
	@Test
	public void rejectsExactDirectionalEquality()
	{
		SequentialPlan lower = SequentialPlan.forPower(probability(95, 99), probability(19, 20),
			probability(99, 100), probability(9, 10));
		SequentialPlan upper = SequentialPlan.forPower(probability(5, 9), probability(19, 20),
			probability(99, 100), probability(9, 10));
		requireThat(lower.decide(new CategoryCounts(1, 1)), "lowerEquality").
			isNotEqualTo(SequentialStatus.CERTIFIED);
		requireThat(lower.bounds(new CategoryCounts(1, 1)).lower(), "displayedLowerEquality").
			isLessThanOrEqualTo(0.95);
		requireThat(upper.decide(new CategoryCounts(0, 1)), "upperEquality").
			isNotEqualTo(SequentialStatus.BELOW_MINIMUM);
		double upperBound = upper.bounds(new CategoryCounts(0, 1)).upper();
		requireThat(probability(19, 20).compareDouble(upperBound), "displayedUpperEquality").
			isGreaterThanOrEqualTo(0);
	}

	/**
	 * Verifies a displayed lower endpoint never rounds above an exact interior root.
	 */
	@Test
	public void roundsInteriorLowerRootDown()
	{
		SequentialPlan plan = SequentialPlan.forPower(probability(45, 49), probability(19, 20),
			probability(99, 100), probability(9, 10));
		double bound = plan.bounds(new CategoryCounts(1, 1)).lower();
		requireThat(probability(9, 10).compareDouble(bound), "rootComparison").isLessThanOrEqualTo(0);
	}

	/**
	 * Verifies an unresolved category stops at its frozen cap and cannot accept an extra result.
	 */
	@Test
	public void exhaustsFrozenCap()
	{
		SequentialPlan plan = SequentialPlan.forPower(LineageAllowance.forCandidate(1),
			probability(19, 20), probability(99, 100), probability(9, 10));
		requireThat(plan.decide(new CategoryCounts(245, plan.cap())), "capResult").
			isEqualTo(SequentialStatus.CAP_EXHAUSTED);
		requireThat(plan.alpha(), "alpha").isEqualTo(LineageAllowance.forCandidate(1));
	}

	/**
	 * Verifies all confirmed categories must cross the lower boundary before certification.
	 */
	@Test
	public void requiresEveryConfirmedCategory()
	{
		SequentialPlan plan = SequentialPlan.forPower(LineageAllowance.forCandidate(1),
			probability(19, 20), probability(99, 100), probability(9, 10));
		SequentialDecision pending = SequentialDecision.evaluate(plan, Set.of("strong", "pending"), Map.of(
			"strong", new CategoryCounts(100, 100), "pending", new CategoryCounts(0, 0)));
		requireThat(pending.status(), "pendingStatus").isEqualTo(SequentialStatus.CONTINUE);
		requireThat(pending.categoryStatuses().get("strong"), "strongStatus").
			isEqualTo(SequentialStatus.CERTIFIED);
		SequentialDecision passed = SequentialDecision.evaluate(plan, Set.of("strong", "pending"), Map.of(
			"strong", new CategoryCounts(100, 100), "pending", new CategoryCounts(100, 100)));
		requireThat(passed.status(), "passedStatus").isEqualTo(SequentialStatus.CERTIFIED);
	}

	/**
	 * Verifies the displayed directional bounds correspond to early decisions.
	 */
	@Test
	public void displaysDirectionalConfidenceBounds()
	{
		SequentialPlan plan = SequentialPlan.forPower(LineageAllowance.forCandidate(1),
			probability(19, 20), probability(99, 100), probability(9, 10));
		requireThat(plan.bounds(new CategoryCounts(0, 0)), "emptyBounds").
			isEqualTo(new SequentialBounds(0.0, 1.0));
		requireThat(plan.bounds(new CategoryCounts(100, 100)).lower(), "lower").isGreaterThan(0.95);
		requireThat(plan.bounds(new CategoryCounts(0, 10)).upper(), "upper").isLessThan(0.95);
	}

	/**
	 * Verifies a different confirmed planning rate changes calibration.
	 */
	@Test
	public void recalibratesForConfirmedPlanningRate()
	{
		SequentialPlan revised = SequentialPlan.forPower(LineageAllowance.forCandidate(1),
			probability(19, 20), probability(197, 200), probability(17, 20));
		requireThat(revised.cap(), "revisedCap").isNotEqualTo(250);
		requireThat(revised.achievedPower().compareTo(probability(17, 20)), "revisedPower").
			isGreaterThanOrEqualTo(0);
	}

	/**
	 * Verifies a perfect planning rate keeps valid limits when a failure is observed.
	 */
	@Test
	public void handlesPerfectPlanningRate()
	{
		SequentialPlan plan = SequentialPlan.forPower(LineageAllowance.forCandidate(1),
			probability(19, 20), probability(1, 1), probability(9, 10));
		requireThat(plan.cap(), "cap").isEqualTo(72);
		requireThat(plan.decide(new CategoryCounts(72, 72)), "allPass").isEqualTo(SequentialStatus.CERTIFIED);
		requireThat(plan.bounds(new CategoryCounts(0, 1)).lower(), "failedLower").isEqualTo(0.0);
	}

	/**
	 * Verifies a below-minimum category ends an attempt despite a certified peer.
	 */
	@Test
	public void belowMinimumTakesPriority()
	{
		SequentialPlan plan = SequentialPlan.forPower(LineageAllowance.forCandidate(1),
			probability(19, 20), probability(99, 100), probability(9, 10));
		SequentialDecision result = SequentialDecision.evaluate(plan, Set.of("strong", "weak"), Map.of(
			"strong", new CategoryCounts(100, 100), "weak", new CategoryCounts(0, 10)));
		requireThat(result.status(), "status").isEqualTo(SequentialStatus.BELOW_MINIMUM);
	}

	/**
	 * Verifies a confirmed category cannot be silently omitted.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMissingConfirmedCategory()
	{
		SequentialPlan plan = SequentialPlan.forPower(LineageAllowance.forCandidate(1),
			probability(19, 20), probability(99, 100), probability(9, 10));
		SequentialDecision.evaluate(plan, Set.of("first", "second"), Map.of(
			"first", new CategoryCounts(100, 100)));
	}

	/**
	 * Verifies observations after the cap cannot enter the current attempt.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsTrialsBeyondCap()
	{
		SequentialPlan plan = SequentialPlan.forPower(LineageAllowance.forCandidate(1),
			probability(19, 20), probability(99, 100), probability(9, 10));
		plan.decide(new CategoryCounts(plan.cap() + 1, plan.cap() + 1));
	}

	/**
	 * Creates an exact probability used by the statistical fixtures.
	 *
	 * @param numerator the successful mass
	 * @param denominator the total mass
	 * @return the exact probability
	 */
	private static RationalProbability probability(long numerator, long denominator)
	{
		return new RationalProbability(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
	}
}
