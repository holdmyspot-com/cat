package com.holdmyspot.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * The balanced benchmark of the confirmed contract, with its weights.
 * <p>
 * The weights are testing assumptions, not usage frequencies, and they do not change with the allocation of trials.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param cells the weight of each held-out test case within each cell, in the contract's cell order
 */
public record Benchmark(List<CellWeights> cells)
{
	/**
	 * The weight of each held-out test case of one cell, whose formal trials draw its prompt test's held-out test cases
	 * uniformly.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param cell the cell's identifier
	 * @param testCases the number of held-out test cases of the cell's prompt test
	 * @param testCaseWeight the benchmark weight of each of those test cases
	 */
	public record CellWeights(String cell, int testCases, double testCaseWeight)
	{
		/**
		 * Creates the weights of a cell.
		 *
		 * @param cell the cell's identifier
		 * @param testCases the number of held-out test cases of the cell's prompt test
		 * @param testCaseWeight the benchmark weight of each of those test cases
		 * @throws NullPointerException if {@code cell} is null
		 * @throws IllegalArgumentException if {@code testCases} is not positive, or {@code testCaseWeight} is outside
		 *   (0, 1]
		 */
		public CellWeights
		{
			requireThat(cell, "cell").isNotNull();
			requireThat(testCases, "testCases").isPositive();
			requireThat(testCaseWeight, "testCaseWeight").isBetween(0.0, false, 1.0, true);
		}
	}

	/**
	 * Creates a benchmark.
	 *
	 * @param cells the weight of each held-out test case within each cell
	 * @throws NullPointerException if {@code cells} is null
	 */
	public Benchmark
	{
		requireThat(cells, "cells").isNotNull();
		cells = List.copyOf(cells);
	}
}
