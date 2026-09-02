package io.github.cowwoc.cat.common.optimization.development;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests deterministic limiting-family-first development prompt_test scheduling.
 */
public final class DevelopmentPromptTestSchedulerTest
{
	/**
	 * Creates the test fixture.
	 */
	public DevelopmentPromptTestSchedulerTest()
	{
	}

	/**
	 * Verifies focused comparisons schedule only limiting and edited families in priority order.
	 */
	@Test
	public void prioritizesLimitingAndEditedFamilies()
	{
		DevelopmentPromptTestScheduler.Schedule schedule = DevelopmentPromptTestScheduler.schedule(policies(),
			DevelopmentComparisonOperation.ComparisonScope.FOCUSED, Set.of("clarity"), Set.of("architecture"), 2, 10);

		requireThat(schedule.familyOrder(), "familyOrder").containsExactly(List.of("clarity", "architecture"));
		requireThat(schedule.promptTests(), "promptTests").size().isEqualTo(4);
		requireThat(schedule.promptTests().stream().map(PromptTestGenerationPolicy.PromptTest::familyIdentifier).toList(),
			"promptTestFamilies").containsExactly(List.of("clarity", "clarity", "architecture", "architecture"));
	}

	/**
	 * Verifies broad comparisons include every frozen family exactly the requested number of times.
	 */
	@Test
	public void schedulesEveryFamilyForBroadComparison()
	{
		DevelopmentPromptTestScheduler.Schedule schedule = DevelopmentPromptTestScheduler.schedule(policies(),
			DevelopmentComparisonOperation.ComparisonScope.ALL_FAMILIES, Set.of("clarity"), Set.of(), 1, 10);

		requireThat(schedule.familyOrder(), "familyOrder").
			containsExactly(List.of("clarity", "architecture", "documentation"));
		requireThat(schedule.promptTests(), "promptTests").size().isEqualTo(3);
	}

	/**
	 * Verifies formal support cannot be exposed to development candidate comparisons.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Development prompt_test policy has the wrong evidence pool: clarity.*")
	public void rejectsFormalPolicy()
	{
		DevelopmentPromptTestScheduler.schedule(List.of(policy("clarity", PromptTestPoolPartition.Pool.FORMAL)),
			DevelopmentComparisonOperation.ComparisonScope.ALL_FAMILIES, Set.of(), Set.of(), 1, 10);
	}

	/**
	 * Creates one policy per frozen family.
	 *
	 * @return the policies
	 */
	private static List<PromptTestGenerationPolicy> policies()
	{
		return List.of(policy("architecture"), policy("clarity"), policy("documentation"));
	}

	/**
	 * Creates one finite generator.
	 *
	 * @param family the family identifier
	 * @return the policy
	 */
	private static PromptTestGenerationPolicy policy(String family)
	{
		return policy(family, PromptTestPoolPartition.Pool.DEVELOPMENT);
	}

	/**
	 * Creates one finite generator for a selected evidence pool.
	 *
	 * @param family the family identifier
	 * @param pool the evidence pool
	 * @return the policy
	 */
	private static PromptTestGenerationPolicy policy(String family, PromptTestPoolPartition.Pool pool)
	{
		return new PromptTestGenerationPolicy(pool, family,
			family + "-distribution", "uniform-v1", family + "-generator", "development-" + family,
			"Use a {{tone}} response.", Map.of("tone", List.of("direct", "plain", "brief")), Set.of(), 10);
	}
}
