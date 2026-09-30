package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.statistics.RationalProbability;
import io.github.cowwoc.cat.common.optimizer.statistics.SequentialDecision;
import io.github.cowwoc.cat.common.optimizer.statistics.SequentialPlan;
import io.github.cowwoc.cat.common.optimizer.statistics.SequentialStatus;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Checks formal category allocation from the attempt's valid outcome counts.
 */
public final class FormalCategorySelectorTest
{
	/**
	 * Creates the test instance.
	 */
	public FormalCategorySelectorTest()
	{
	}

	/**
	 * Every category receives an initial trial before allocation uses its bounds.
	 */
	@Test
	public void coversEveryCategoryFirst()
	{
		SequentialPlan plan = plan();
		List<String> categories = List.of("first", "second");
		requireThat(FormalCategorySelector.select(plan, categories, Map.of(
			"first", new CategoryCounts(0, 0), "second", new CategoryCounts(0, 0))).orElseThrow(),
			"firstCategory").isEqualTo("first");
		requireThat(FormalCategorySelector.select(plan, categories, Map.of(
			"first", new CategoryCounts(1, 1), "second", new CategoryCounts(0, 0))).orElseThrow(),
			"secondCategory").isEqualTo("second");
	}

	/**
	 * After initial coverage, the unresolved category with the lower bound receives the next trial.
	 */
	@Test
	public void choosesLowestUnresolvedBound()
	{
		SequentialPlan plan = plan();
		Map<String, CategoryCounts> counts = Map.of("first", new CategoryCounts(99, 100),
			"second", new CategoryCounts(98, 100));
		SequentialDecision decision = SequentialDecision.evaluate(plan, counts.keySet(), counts);
		requireThat(decision.categoryStatuses().get("first"), "firstUnresolved").isEqualTo(
			SequentialStatus.CONTINUE);
		requireThat(decision.categoryStatuses().get("second"), "secondUnresolved").isEqualTo(
			SequentialStatus.CONTINUE);
		String selected = FormalCategorySelector.select(plan, List.of("first", "second"), counts).orElseThrow();
		requireThat(selected, "lowestBoundCategory").isEqualTo("second");
	}

	/**
	 * Certified categories receive no further formal trials.
	 */
	@Test
	public void skipsCertifiedCategory()
	{
		SequentialPlan plan = plan();
		Map<String, CategoryCounts> counts = Map.of("first", new CategoryCounts(100, 100),
			"second", new CategoryCounts(99, 100));
		SequentialDecision decision = SequentialDecision.evaluate(plan, counts.keySet(), counts);
		requireThat(decision.categoryStatuses().get("first"), "certifiedCategory").isEqualTo(
			SequentialStatus.CERTIFIED);
		String selected = FormalCategorySelector.select(plan, List.of("first", "second"), counts).orElseThrow();
		requireThat(selected, "unresolvedCategory").isEqualTo("second");
	}

	/**
	 * Equal lower bounds prefer fewer outcomes, then confirmed category order.
	 */
	@Test
	public void resolvesAllocationTies()
	{
		SequentialPlan plan = plan();
		List<String> categories = List.of("first", "second");
		String fewer = FormalCategorySelector.select(plan, categories, Map.of(
			"first", new CategoryCounts(0, 2), "second", new CategoryCounts(0, 1))).orElseThrow();
		requireThat(fewer, "fewerOutcomes").isEqualTo("second");
		String first = FormalCategorySelector.select(plan, categories, Map.of(
			"first", new CategoryCounts(0, 1), "second", new CategoryCounts(0, 1))).orElseThrow();
		requireThat(first, "confirmedOrder").isEqualTo("first");
	}

	/**
	 * A below-minimum decision stops allocation before another category can draw.
	 */
	@Test
	public void stopsAfterBelowMinimum()
	{
		SequentialPlan plan = plan();
		Map<String, CategoryCounts> counts = Map.of("first", new CategoryCounts(0, 10),
			"second", new CategoryCounts(0, 0));
		SequentialDecision decision = SequentialDecision.evaluate(plan, counts.keySet(), counts);
		requireThat(decision.status(), "terminalDecision").isEqualTo(SequentialStatus.BELOW_MINIMUM);
		requireThat(FormalCategorySelector.select(plan, List.of("first", "second"), counts).isEmpty(),
			"selectionAbsent").isTrue();
	}

	/**
	 * Certification of every category ends formal allocation without another draw.
	 */
	@Test
	public void stopsAfterEveryCategoryCertifies()
	{
		SequentialPlan plan = plan();
		Map<String, CategoryCounts> counts = Map.of("first", new CategoryCounts(100, 100),
			"second", new CategoryCounts(100, 100));
		SequentialDecision decision = SequentialDecision.evaluate(plan, counts.keySet(), counts);
		requireThat(decision.status(), "terminalDecision").isEqualTo(SequentialStatus.CERTIFIED);
		requireThat(FormalCategorySelector.select(plan, List.of("first", "second"), counts).isEmpty(),
			"selectionAbsent").isTrue();
	}

	/**
	 * Creates a small exact sequential plan shared by the formal allocation claims.
	 *
	 * @return frozen test procedure
	 */
	private static SequentialPlan plan()
	{
		return SequentialPlan.forAttempt(1, probability(19, 20), probability(99, 100),
			probability(9, 10));
	}

	/**
	 * Creates one exact rational planning input.
	 *
	 * @param numerator probability numerator
	 * @param denominator probability denominator
	 * @return exact probability
	 */
	private static RationalProbability probability(int numerator, int denominator)
	{
		return new RationalProbability(
			BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
	}
}
