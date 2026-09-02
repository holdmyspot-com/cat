package io.github.cowwoc.cat.common.optimization.development;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests non-overlapping development, readiness, and formal prompt-test pools.
 */
public final class PromptTestPoolPartitionTest
{
	private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	/**
	 * Creates the test fixture.
	 */
	public PromptTestPoolPartitionTest()
	{
	}

	/**
	 * Verifies independent seeds, instances, generated content, and executions are eligible.
	 */
	@Test
	public void acceptsDisjointPools()
	{
		PromptTestPoolPartition.Result result = PromptTestPoolPartition.validate(List.of(
			promptTest(PromptTestPoolPartition.Pool.DEVELOPMENT, "dev", "seed-dev", SHA_A, "run-dev"),
			promptTest(PromptTestPoolPartition.Pool.FORMAL, "formal", "seed-formal", SHA_B, "run-formal")));

		requireThat(result.status(), "status").isEqualTo(PromptTestPoolPartition.Status.ELIGIBLE);
	}

	/**
	 * Verifies a seed collision across pools blocks execution before inference.
	 */
	@Test
	public void blocksCrossPoolSeedCollision()
	{
		PromptTestPoolPartition.Result result = PromptTestPoolPartition.validate(List.of(
			promptTest(PromptTestPoolPartition.Pool.READINESS, "ready", "same-seed", SHA_A, "run-ready"),
			promptTest(PromptTestPoolPartition.Pool.FORMAL, "formal", "same-seed", SHA_B, "run-formal")));

		requireThat(result.status(), "status").isEqualTo(PromptTestPoolPartition.Status.BLOCKED);
		requireThat(result.collisions().getFirst().field(), "field").isEqualTo(
			PromptTestPoolPartition.IdentityField.SEED);
	}

	/**
	 * Verifies the same generated content cannot move between development and formal evidence under new identifiers.
	 */
	@Test
	public void blocksContentReuseAcrossPools()
	{
		PromptTestPoolPartition.Result result = PromptTestPoolPartition.validate(List.of(
			promptTest(PromptTestPoolPartition.Pool.DEVELOPMENT, "dev", "seed-dev", SHA_A, "run-dev"),
			promptTest(PromptTestPoolPartition.Pool.FORMAL, "formal", "seed-formal", SHA_A, "run-formal")));

		requireThat(result.status(), "status").isEqualTo(PromptTestPoolPartition.Status.BLOCKED);
		requireThat(result.collisions().getFirst().field(), "field").isEqualTo(
			PromptTestPoolPartition.IdentityField.GENERATED_CONTENT_FINGERPRINT);
	}

	/**
	 * Creates one prompt-test identity.
	 *
	 * @param pool the evidence pool
	 * @param instance the prompt-test instance identifier
	 * @param seed the seed identifier
	 * @param contentFingerprint the generated-content fingerprint
	 * @param execution the retained execution identifier
	 * @return the prompt-test identity
	 */
	private static PromptTestPoolPartition.Identity promptTest(PromptTestPoolPartition.Pool pool, String instance,
		String seed, String contentFingerprint, String execution)
	{
		return new PromptTestPoolPartition.Identity(pool, instance, seed, contentFingerprint, execution);
	}
}
