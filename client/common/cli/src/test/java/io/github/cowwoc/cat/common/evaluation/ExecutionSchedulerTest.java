package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

import org.testng.annotations.Test;

/**
 * Tests {@link ExecutionScheduler}.
 */
public final class ExecutionSchedulerTest
{
	/**
	 * Creates the TestNG test instance.
	 */
	public ExecutionSchedulerTest()
	{
	}

	/**
	 * Verifies rounds preserve the caller's trial order while respecting the capacity limit.
	 */
	@Test
	public void partitionsTrialsInOrder()
	{
		List<List<Integer>> rounds = ExecutionScheduler.rounds(List.of(3, 5, 7, 11, 13), 2);

		requireThat(rounds, "rounds").isEqualTo(List.of(List.of(3, 5), List.of(7, 11), List.of(13)));
	}
}
