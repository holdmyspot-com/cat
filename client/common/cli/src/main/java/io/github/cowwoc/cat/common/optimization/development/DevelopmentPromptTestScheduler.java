package io.github.cowwoc.cat.common.optimization.development;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Generates a deterministic paired-prompt_test batch with limiting and edited families first.
 */
public final class DevelopmentPromptTestScheduler
{
	/**
	 * Prevents construction of this static scheduling owner.
	 */
	private DevelopmentPromptTestScheduler()
	{
	}

	/**
	 * Generates one focused or all-family development batch.
	 *
	 * @param policies the exact executable policy for every frozen family
	 * @param scope the comparison scope
	 * @param limitingFamilies families limiting the current best candidate
	 * @param editedFamilies families affected by the proposed revision
	 * @param pairsPerFamily the exact new paired promptTests per selected family
	 * @param initialSeed the first immutable generator seed
	 * @return the ordered generated batch
	 */
	public static Schedule schedule(List<PromptTestGenerationPolicy> policies,
		DevelopmentComparisonOperation.ComparisonScope scope, Set<String> limitingFamilies, Set<String> editedFamilies,
		int pairsPerFamily, long initialSeed)
	{
		Objects.requireNonNull(policies, "policies must not be null");
		Objects.requireNonNull(scope, "scope must not be null");
		Objects.requireNonNull(limitingFamilies, "limitingFamilies must not be null");
		Objects.requireNonNull(editedFamilies, "editedFamilies must not be null");
		if (pairsPerFamily <= 0)
			throw new IllegalArgumentException("pairsPerFamily must be positive");
		Map<String, PromptTestGenerationPolicy> byFamily = new HashMap<>();
		for (PromptTestGenerationPolicy policy : policies)
		{
			Objects.requireNonNull(policy, "policies must not contain null elements");
			if (policy.pool() != PromptTestPoolPartition.Pool.DEVELOPMENT)
			{
				throw new IllegalArgumentException("Development prompt_test policy has the wrong evidence pool: " +
					policy.familyIdentifier());
			}
			if (byFamily.putIfAbsent(policy.familyIdentifier(), policy) != null)
				throw new IllegalArgumentException("Duplicate prompt_test policy: " + policy.familyIdentifier());
		}
		if (byFamily.isEmpty())
			throw new IllegalArgumentException("policies must not be empty");
		validateFamilies(limitingFamilies, byFamily.keySet(), "limitingFamilies");
		validateFamilies(editedFamilies, byFamily.keySet(), "editedFamilies");

		List<String> familyOrder = familyOrder(scope, byFamily.keySet(), limitingFamilies, editedFamilies);
		List<PromptTestGenerationPolicy.PromptTest> promptTests = new ArrayList<>(familyOrder.size() * pairsPerFamily);
		long seed = initialSeed;
		for (String family : familyOrder)
		{
			for (int pair = 0; pair < pairsPerFamily; ++pair)
			{
				promptTests.add(byFamily.get(family).generate(seed));
				seed = Math.addExact(seed, 1);
			}
		}
		return new Schedule(familyOrder, promptTests, initialSeed, seed);
	}

	/**
	 * Selects the canonical family order.
	 *
	 * @param scope the comparison scope
	 * @param allFamilies every frozen family
	 * @param limitingFamilies limiting families
	 * @param editedFamilies edited families
	 * @return limiting, edited, then remaining families
	 */
	private static List<String> familyOrder(DevelopmentComparisonOperation.ComparisonScope scope,
		Set<String> allFamilies, Set<String> limitingFamilies, Set<String> editedFamilies)
	{
		List<String> result = new ArrayList<>(allFamilies.size());
		addSorted(result, limitingFamilies);
		Set<String> editedOnly = new HashSet<>(editedFamilies);
		editedOnly.removeAll(limitingFamilies);
		addSorted(result, editedOnly);
		if (scope == DevelopmentComparisonOperation.ComparisonScope.ALL_FAMILIES || result.isEmpty())
		{
			Set<String> remaining = new HashSet<>(allFamilies);
			remaining.removeAll(result);
			addSorted(result, remaining);
		}
		return List.copyOf(result);
	}

	/**
	 * Appends values in lexical order.
	 *
	 * @param destination the destination
	 * @param values the values
	 */
	private static void addSorted(List<String> destination, Set<String> values)
	{
		destination.addAll(new TreeSet<>(values));
	}

	/**
	 * Validates that selected families belong to the frozen contract.
	 *
	 * @param values the selected families
	 * @param available the frozen families
	 * @param name the parameter name
	 */
	private static void validateFamilies(Set<String> values, Set<String> available, String name)
	{
		for (String value : values)
		{
			if (value == null)
				throw new NullPointerException(name + " must not contain null elements");
			if (!available.contains(value))
				throw new IllegalArgumentException(name + " contains an unknown family: " + value);
		}
	}

	/**
	 * Holds one generated development batch.
	 *
	 * @param familyOrder the scheduled family priority
	 * @param promptTests the generated promptTests
	 * @param initialSeed the first consumed seed
	 * @param nextSeed the first unconsumed seed
	 */
	public record Schedule(List<String> familyOrder, List<PromptTestGenerationPolicy.PromptTest> promptTests,
		long initialSeed, long nextSeed)
	{
		/**
		 * Snapshots one schedule.
		 *
		 * @param familyOrder the scheduled family priority
		 * @param promptTests the generated promptTests
		 * @param initialSeed the first consumed seed
		 * @param nextSeed the first unconsumed seed
		 */
		public Schedule
		{
			familyOrder = List.copyOf(familyOrder);
			promptTests = List.copyOf(promptTests);
		}
	}
}
