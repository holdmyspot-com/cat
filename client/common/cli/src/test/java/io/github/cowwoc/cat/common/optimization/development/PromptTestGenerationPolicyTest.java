package io.github.cowwoc.cat.common.optimization.development;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests deterministic within-family superficial prompt-test variation and support validation.
 */
public final class PromptTestGenerationPolicyTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptTestGenerationPolicyTest()
	{
	}

	/**
	 * Verifies the same seed produces the same supported promptTest and distinct seeds remain in the declared support.
	 */
	@Test
	public void reproducesSupportedPromptTest()
	{
		PromptTestGenerationPolicy policy = policy(Set.of(
			new PromptTestGenerationPolicy.ExcludedCombination(Map.of("name", "Ada", "path", "src/B.java"))));

		PromptTestGenerationPolicy.Validation validation = policy.validateSupport();
		PromptTestGenerationPolicy.PromptTest first = policy.generate(42);
		PromptTestGenerationPolicy.PromptTest repeated = policy.generate(42);
		PromptTestGenerationPolicy.PromptTest another = policy.generate(43);

		requireThat(validation.status(), "validation.status()").isEqualTo(
			PromptTestGenerationPolicy.ValidationStatus.SUPPORTED);
		requireThat(validation.validCombinationCount(), "validCombinationCount").isEqualTo(1L);
		requireThat(first, "first").isEqualTo(repeated);
		requireThat(policy.contains(first), "policy.contains(first)").isTrue();
		requireThat(policy.contains(another), "policy.contains(another)").isTrue();
		requireThat(first.renderedPrompt().contains("{{"), "unresolvedPlaceholder").isFalse();
	}

	/**
	 * Verifies development, readiness, and formal policies expose disjoint portions of one finite base support.
	 */
	@Test
	public void partitionsPromptTestContentAcrossPools()
	{
		PromptTestGenerationPolicy development = partitionPolicy(PromptTestPoolPartition.Pool.DEVELOPMENT);
		PromptTestGenerationPolicy readiness = partitionPolicy(PromptTestPoolPartition.Pool.READINESS);
		PromptTestGenerationPolicy formal = partitionPolicy(PromptTestPoolPartition.Pool.FORMAL);
		PromptTestGenerationPolicy.PromptTest developmentPromptTest = development.generate(42);
		PromptTestGenerationPolicy.PromptTest formalPromptTest = formal.generate(42);
		PromptTestPoolPartition.Result collisionCheck = PromptTestPoolPartition.validate(List.of(
			identity(developmentPromptTest, "development-run"), identity(formalPromptTest, "formal-run")));

		long partitionedCount = development.validateSupport().validCombinationCount() +
			readiness.validateSupport().validCombinationCount() + formal.validateSupport().validCombinationCount();
		requireThat(partitionedCount, "partitionedCount").isEqualTo(6L);
		requireThat(collisionCheck.status(), "collisionCheck.status()").isEqualTo(
			PromptTestPoolPartition.Status.ELIGIBLE);
	}

	/**
	 * Verifies an undeclared template dimension is rejected before generation.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Template references undeclared dimension: language.*")
	public void rejectsUndeclaredTemplateDimension()
	{
		new PromptTestGenerationPolicy(PromptTestPoolPartition.Pool.DEVELOPMENT, "family-clear", "dist-clear",
			"uniform-finite-v1", "generator-v1", "clear-v1", "Explain {{name}} in {{language}}.",
			Map.of("name", List.of("Ada")), Set.of(), 1_000);
	}

	/**
	 * Verifies a policy with no permitted combination blocks instead of inventing a promptTest.
	 */
	@Test
	public void blocksEmptySupport()
	{
		PromptTestGenerationPolicy policy = policy(Set.of(
			new PromptTestGenerationPolicy.ExcludedCombination(Map.of("name", "Ada", "path", "src/A.java")),
			new PromptTestGenerationPolicy.ExcludedCombination(Map.of("name", "Ada", "path", "src/B.java")),
			new PromptTestGenerationPolicy.ExcludedCombination(Map.of("name", "Lin", "path", "src/A.java")),
			new PromptTestGenerationPolicy.ExcludedCombination(Map.of("name", "Lin", "path", "src/B.java"))));

		PromptTestGenerationPolicy.Validation validation = policy.validateSupport();

		requireThat(validation.status(), "validation.status()").isEqualTo(
			PromptTestGenerationPolicy.ValidationStatus.BLOCKED);
		requireThat(validation.reason().orElseThrow(), "reason").isEqualTo(
			PromptTestGenerationPolicy.BlockReason.EMPTY_SUPPORT);
	}

	/**
	 * Creates one finite policy fixture.
	 *
	 * @param exclusions excluded combinations
	 * @return the policy
	 */
	private static PromptTestGenerationPolicy policy(Set<PromptTestGenerationPolicy.ExcludedCombination> exclusions)
	{
		return new PromptTestGenerationPolicy(PromptTestPoolPartition.Pool.DEVELOPMENT, "family-clear", "dist-clear",
			"uniform-finite-v1", "generator-v1", "clear-v1", "Explain {{name}} using the file {{path}}.",
			Map.of("name", List.of("Ada", "Lin"), "path", List.of("src/A.java", "src/B.java")), exclusions, 1_000);
	}

	/**
	 * Creates one evidence-pool view over the same six-combination base support.
	 *
	 * @param pool the selected evidence pool
	 * @return the pool-specific policy
	 */
	private static PromptTestGenerationPolicy partitionPolicy(PromptTestPoolPartition.Pool pool)
	{
		String poolName = pool.name().toLowerCase(Locale.ROOT);
		return new PromptTestGenerationPolicy(pool, "family-clear", poolName + "-dist",
			"uniform-finite-v1", "generator-v1", poolName, "Explain {{name}} at {{path}}.",
			Map.of("name", List.of("Ada", "Lin"), "path", List.of("A", "B", "C")), Set.of(), 1_000);
	}

	/**
	 * Converts one generated promptTest to the cross-pool identity checked before inference.
	 *
	 * @param promptTest the generated promptTest
	 * @param executionIdentifier the planned execution identifier
	 * @return the complete identity
	 */
	private static PromptTestPoolPartition.Identity identity(PromptTestGenerationPolicy.PromptTest promptTest,
		String executionIdentifier)
	{
		return new PromptTestPoolPartition.Identity(promptTest.pool(), promptTest.instanceIdentifier(),
			promptTest.seedNamespace() + ":" + Long.toUnsignedString(promptTest.seed()), promptTest.contentFingerprint(),
			executionIdentifier);
	}
}
