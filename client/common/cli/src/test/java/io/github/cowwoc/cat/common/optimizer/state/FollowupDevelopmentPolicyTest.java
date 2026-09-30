package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Checks post-coverage allocation using retained representative counts.
 */
public final class FollowupDevelopmentPolicyTest
{
	/** Creates the test fixture. */
	public FollowupDevelopmentPolicyTest()
	{
	}

	/** Coverage visits ready categories and skips only categories with a scheduling stop. */
	@Test
	public void cyclesCoverageAcrossEligibleCategories()
	{
		List<String> categories = List.of("first", "second", "third");
		Map<String, CategoryCounts> counts = Map.of("first", new CategoryCounts(50, 50),
			"second", new CategoryCounts(50, 50), "third", new CategoryCounts(45, 50));
		FollowupDevelopmentPolicy.Selection first = FollowupDevelopmentPolicy.select(categories,
			Set.of("first", "third"), Set.of("third"), counts, 50, 0,
			FollowupDevelopmentPolicy.Turn.COVERAGE).orElseThrow();
		requireThat(first.categoryId(), "category").isEqualTo("first");
		requireThat(first.nextCoverageCursor(), "cursor").isEqualTo(1);
		requireThat(first.nextTurn(), "turn").isEqualTo(FollowupDevelopmentPolicy.Turn.ADAPTIVE);

		FollowupDevelopmentPolicy.Selection second = FollowupDevelopmentPolicy.select(categories,
			Set.of("first", "third"), Set.of("third"), counts, 50, first.nextCoverageCursor(),
			first.nextTurn()).orElseThrow();
		requireThat(second.categoryId(), "category").isEqualTo("third");
		requireThat(second.nextCoverageCursor(), "cursor").isEqualTo(1);
		requireThat(second.nextTurn(), "turn").isEqualTo(FollowupDevelopmentPolicy.Turn.COVERAGE);

		FollowupDevelopmentPolicy.Selection third = FollowupDevelopmentPolicy.select(categories,
			Set.of("first", "third"), Set.of("third"), counts, 50, second.nextCoverageCursor(),
			second.nextTurn()).orElseThrow();
		requireThat(third.categoryId(), "category").isEqualTo("third");
		requireThat(third.nextCoverageCursor(), "cursor").isEqualTo(0);
	}

	/** Adaptive ranking uses the current-candidate Wilson lower endpoint before trial count and confirmed order. */
	@Test
	public void prioritizesExploratoryLowerBound()
	{
		FollowupDevelopmentPolicy.Selection choice = FollowupDevelopmentPolicy.select(
			List.of("first", "second", "third"), Set.of("first", "second", "third"),
			Set.of("first", "second", "third"), Map.of("first", new CategoryCounts(45, 50),
				"second", new CategoryCounts(92, 100), "third", new CategoryCounts(50, 50)),
			50, 2, FollowupDevelopmentPolicy.Turn.ADAPTIVE).orElseThrow();
		requireThat(choice.categoryId(), "category").isEqualTo("first");
		requireThat(choice.nextCoverageCursor(), "cursor").isEqualTo(2);
		requireThat(choice.nextTurn(), "turn").isEqualTo(FollowupDevelopmentPolicy.Turn.COVERAGE);
	}

	/** Equal exploratory bounds prefer fewer observations, then confirmed category order. */
	@Test
	public void breaksEqualBoundTiesByCountThenOrder()
	{
		List<String> categories = List.of("first", "second", "third");
		Map<String, CategoryCounts> counts = Map.of("first", new CategoryCounts(0, 100),
			"second", new CategoryCounts(0, 50), "third", new CategoryCounts(0, 50));
		FollowupDevelopmentPolicy.Selection choice = FollowupDevelopmentPolicy.select(categories,
			Set.copyOf(categories), Set.copyOf(categories), counts, 50, 0,
			FollowupDevelopmentPolicy.Turn.ADAPTIVE).orElseThrow();
		requireThat(choice.categoryId(), "category").isEqualTo("second");
	}

	/** No further batch is chosen when every eligible category is ready or awaiting another decision. */
	@Test
	public void stopsWhenNoUnresolvedCategoryIsEligible()
	{
		requireThat(FollowupDevelopmentPolicy.select(List.of("first", "second"),
			Set.of("first"), Set.of(), Map.of("first", new CategoryCounts(50, 50),
				"second", new CategoryCounts(50, 50)), 50, 0,
			FollowupDevelopmentPolicy.Turn.COVERAGE), "selection").isEmpty();
	}

	/** A follow-up choice requires counted first-batch evidence for every confirmed category. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMissingFirstCoverageEvidence()
	{
		FollowupDevelopmentPolicy.select(List.of("first", "second"), Set.of("first", "second"),
			Set.of("first"), Map.of("first", new CategoryCounts(50, 50)), 50, 0,
			FollowupDevelopmentPolicy.Turn.COVERAGE);
	}
}
