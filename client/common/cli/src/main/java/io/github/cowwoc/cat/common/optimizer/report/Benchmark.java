package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * The balanced benchmark of the confirmed contract, with its weights.
 * <p>
 * The weights are testing assumptions, not usage frequencies, and they do not change with the allocation of trials.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param cells the weight of each test within each cell, in the contract's cell order
 */
public record Benchmark(List<CellWeights> cells)
{
	/**
	 * The weight of each test of one cell, which runs its category's tests.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param cell the cell's identifier
	 * @param tests the number of tests in the cell's category
	 * @param testWeight the benchmark weight of each test within the category
	 */
	public record CellWeights(String cell, int tests, double testWeight)
	{
		/**
		 * Creates the weights of a cell.
		 *
		 * @param cell the cell's identifier
		 * @param tests the number of tests in the cell's category
		 * @param testWeight the benchmark weight of each test within the category
		 * @throws NullPointerException if {@code cell} is null
		 * @throws IllegalArgumentException if {@code tests} is not positive, or {@code testWeight} is outside (0, 1]
		 */
		public CellWeights
		{
			requireThat(cell, "cell").isNotNull();
			requireThat(tests, "tests").isPositive();
			requireThat(testWeight, "testWeight").isBetween(0.0, false, 1.0, true);
		}
	}

	/**
	 * Creates a benchmark.
	 *
	 * @param cells the weight of each test within each cell
	 * @throws NullPointerException if {@code cells} is null
	 */
	public Benchmark
	{
		requireThat(cells, "cells").isNotNull();
		cells = List.copyOf(cells);
	}
}
