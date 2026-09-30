package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Checks balanced first coverage using counts supplied at the policy boundary.
 */
public final class InitialCoveragePolicyTest
{
	/** Creates the test fixture. */
	public InitialCoveragePolicyTest()
	{
	}

	/**
	 * An incomplete first batch retains its category, including while an assigned trial awaits an outcome.
	 */
	@Test
	public void retainsIncompleteFirstBatch()
	{
		InitialCoveragePolicy.Selection selected = InitialCoveragePolicy.select(List.of("first", "second"),
			Map.of("first", new InitialCoveragePolicy.Progress(50, 49)), 50).orElseThrow();
		requireThat(selected.categoryId(), "category").isEqualTo("first");
		requireThat(selected.batchNumber(), "batchNumber").isEqualTo(0);
	}

	/**
	 * Fifty assigned and counted outcomes advance balanced coverage to the second confirmed category.
	 */
	@Test
	public void advancesAfterCompleteFirstBatch()
	{
		InitialCoveragePolicy.Selection selected = InitialCoveragePolicy.select(List.of("first", "second"),
			Map.of("first", new InitialCoveragePolicy.Progress(50, 50)), 50).orElseThrow();
		requireThat(selected.categoryId(), "category").isEqualTo("second");
		requireThat(selected.batchNumber(), "batchNumber").isEqualTo(1);
	}

	/**
	 * The first coverage stage ends when every confirmed category has its counted batch.
	 */
	@Test
	public void endsAfterEveryFirstBatch()
	{
		requireThat(InitialCoveragePolicy.select(List.of("first", "second"),
			Map.of("first", new InitialCoveragePolicy.Progress(50, 50),
				"second", new InitialCoveragePolicy.Progress(50, 50)), 50), "firstCoverageSelection").isEmpty();
	}

	/**
	 * A later category cannot start while an earlier category still has an unfinished assignment.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsOutOfOrderProgress()
	{
		InitialCoveragePolicy.select(List.of("first", "second"),
			Map.of("first", new InitialCoveragePolicy.Progress(50, 49),
				"second", new InitialCoveragePolicy.Progress(1, 0)), 50);
	}
}
