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
 * @param categories the weight of each test within each category, in the contract's category order
 */
public record Benchmark(List<CategoryWeights> categories)
{
	/**
	 * The weight of each test of one category.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param category the category
	 * @param tests the number of tests in the category
	 * @param testWeight the benchmark weight of each test within the category
	 */
	public record CategoryWeights(String category, int tests, double testWeight)
	{
		/**
		 * Creates the weights of a category.
		 *
		 * @param category the category
		 * @param tests the number of tests in the category
		 * @param testWeight the benchmark weight of each test within the category
		 * @throws NullPointerException if {@code category} is null
		 * @throws IllegalArgumentException if {@code tests} is not positive, or {@code testWeight} is outside (0, 1]
		 */
		public CategoryWeights
		{
			requireThat(category, "category").isNotNull();
			requireThat(tests, "tests").isPositive();
			requireThat(testWeight, "testWeight").isBetween(0.0, false, 1.0, true);
		}
	}

	/**
	 * Creates a benchmark.
	 *
	 * @param categories the weight of each test within each category
	 * @throws NullPointerException if {@code categories} is null
	 */
	public Benchmark
	{
		requireThat(categories, "categories").isNotNull();
		categories = List.copyOf(categories);
	}
}
