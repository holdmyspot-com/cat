package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Checks category allocation after every eligible category has received its first batch.
 */
public final class DevelopmentBatchSelectorTest
{
	/**
	 * Creates the test instance.
	 */
	public DevelopmentBatchSelectorTest()
	{
	}

	/**
	 * Coverage follows confirmed order, including a ready category, and advances only its own cursor.
	 */
	@Test
	public void cyclesCoverageThroughEligibleCategories()
	{
		List<String> categories = List.of("first", "second", "third");
		Map<String, CategoryCounts> totals = Map.of("first", new CategoryCounts(50, 50),
			"second", new CategoryCounts(49, 50), "third", new CategoryCounts(50, 50));
		DevelopmentBatchSelector.Progress start = new DevelopmentBatchSelector.Progress(
			DevelopmentBatchSelector.Turn.COVERAGE, 0);
		DevelopmentBatchSelector.Selection first = DevelopmentBatchSelector.select(categories, totals,
			Set.of("first", "second"), Set.of("second"), start).orElseThrow();
		requireThat(first.categoryId(), "firstCoverageCategory").isEqualTo("first");
		requireThat(first.turn(), "firstTurn").isEqualTo(DevelopmentBatchSelector.Turn.COVERAGE);
		requireThat(first.next(), "afterFirstCoverage").isEqualTo(new DevelopmentBatchSelector.Progress(
			DevelopmentBatchSelector.Turn.ADAPTIVE, 1));

		DevelopmentBatchSelector.Selection adaptive = DevelopmentBatchSelector.select(categories, totals,
			Set.of("first", "second"), Set.of("second"), first.next()).orElseThrow();
		requireThat(adaptive.categoryId(), "adaptiveCategory").isEqualTo("second");
		requireThat(adaptive.next(), "afterAdaptive").isEqualTo(new DevelopmentBatchSelector.Progress(
			DevelopmentBatchSelector.Turn.COVERAGE, 1));

		DevelopmentBatchSelector.Selection second = DevelopmentBatchSelector.select(categories, totals,
			Set.of("first", "second"), Set.of("second"), adaptive.next()).orElseThrow();
		requireThat(second.categoryId(), "secondCoverageCategory").isEqualTo("second");
		requireThat(second.next().coverageCursor(), "nextCoverageCursor").isEqualTo(2);

		DevelopmentBatchSelector.Selection wrapped = DevelopmentBatchSelector.select(categories, totals,
			Set.of("first", "second"), Set.of("second"), new DevelopmentBatchSelector.Progress(
				DevelopmentBatchSelector.Turn.COVERAGE, 2)).orElseThrow();
		requireThat(wrapped.categoryId(), "coverageAfterIneligibleCategory").isEqualTo("first");
	}

	/**
	 * After 150 counted trials across two categories, the weaker lower bound wins the adaptive turn.
	 */
	@Test
	public void prioritizesWeakerAggregateResult()
	{
		DevelopmentBatchSelector.Selection selected = DevelopmentBatchSelector.select(
			List.of("first", "second"),
			Map.of("first", new CategoryCounts(99, 100), "second", new CategoryCounts(50, 50)),
			Set.of("first", "second"), Set.of("first", "second"),
			new DevelopmentBatchSelector.Progress(DevelopmentBatchSelector.Turn.ADAPTIVE, 1)).orElseThrow();
		requireThat(selected.categoryId(), "adaptiveCategory").isEqualTo("second");
		requireThat(selected.next(), "nextCoverageTurn").isEqualTo(new DevelopmentBatchSelector.Progress(
			DevelopmentBatchSelector.Turn.COVERAGE, 1));
	}

	/**
	 * Adaptive allocation chooses the unresolved eligible category with the weakest lower bound.
	 */
	@Test
	public void prioritizesLowestWilsonLowerBound()
	{
		DevelopmentBatchSelector.Selection selection = DevelopmentBatchSelector.select(
			List.of("first", "second", "third"),
			Map.of("first", new CategoryCounts(49, 50), "second", new CategoryCounts(47, 50),
				"third", new CategoryCounts(40, 50)),
			Set.of("first", "second"), Set.of("first", "second", "third"),
			new DevelopmentBatchSelector.Progress(DevelopmentBatchSelector.Turn.ADAPTIVE, 2)).orElseThrow();
		requireThat(selection.categoryId(), "lowestEligibleCategory").isEqualTo("second");
		requireThat(selection.next(), "afterAdaptive").isEqualTo(new DevelopmentBatchSelector.Progress(
			DevelopmentBatchSelector.Turn.COVERAGE, 2));
	}

	/**
	 * Equal lower bounds prefer fewer observations, then confirmed order.
	 */
	@Test
	public void resolvesAdaptiveTies()
	{
		List<String> categories = List.of("first", "second", "third");
		DevelopmentBatchSelector.Progress progress = new DevelopmentBatchSelector.Progress(
			DevelopmentBatchSelector.Turn.ADAPTIVE, 1);
		DevelopmentBatchSelector.Selection fewer = DevelopmentBatchSelector.select(categories,
			Map.of("first", new CategoryCounts(0, 2), "second", new CategoryCounts(0, 1),
				"third", new CategoryCounts(50, 50)),
			Set.copyOf(categories), Set.copyOf(categories), progress).orElseThrow();
		requireThat(fewer.categoryId(), "fewerObservations").isEqualTo("second");

		DevelopmentBatchSelector.Selection confirmedOrder = DevelopmentBatchSelector.select(categories,
			Map.of("first", new CategoryCounts(49, 50), "second", new CategoryCounts(49, 50),
				"third", new CategoryCounts(50, 50)),
			Set.copyOf(categories), Set.copyOf(categories), progress).orElseThrow();
		requireThat(confirmedOrder.categoryId(), "confirmedOrder").isEqualTo("first");
	}

	/**
	 * An adaptive turn cannot manufacture a batch when no unresolved eligible category remains.
	 */
	@Test
	public void skipsAdaptiveWhenAllReady()
	{
		requireThat(DevelopmentBatchSelector.select(List.of("first", "second"),
			Map.of("first", new CategoryCounts(50, 50), "second", new CategoryCounts(49, 50)),
			Set.of("first", "second"), Set.of(),
			new DevelopmentBatchSelector.Progress(DevelopmentBatchSelector.Turn.ADAPTIVE, 0)).isEmpty(),
			"adaptiveChoiceAbsent").isTrue();
	}
}
