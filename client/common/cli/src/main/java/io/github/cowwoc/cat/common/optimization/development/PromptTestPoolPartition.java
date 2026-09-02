package io.github.cowwoc.cat.common.optimization.development;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Rejects prompt_test identity reuse across development, readiness, and formal evidence pools before inference.
 */
public final class PromptTestPoolPartition
{
	/**
	 * Creates the canonical pool identity for one generated prompt_test and retained execution.
	 *
	 * @param promptTest the generated promptTest
	 * @param executionIdentifier the retained execution identifier
	 * @return the canonical pool identity
	 */
	public static Identity createIdentity(PromptTestGenerationPolicy.PromptTest promptTest, String executionIdentifier)
	{
		if (promptTest == null)
			throw new NullPointerException("promptTest must not be null");
		return new Identity(promptTest.pool(), promptTest.instanceIdentifier(), promptTest.seedNamespace() + ":" +
			promptTest.pool().name() + ":" + Long.toUnsignedString(promptTest.seed()), promptTest.contentFingerprint(),
			executionIdentifier);
	}

	/**
	 * Prevents construction of this static validation owner.
	 */
	private PromptTestPoolPartition()
	{
	}

	/**
	 * Validates the complete known prompt_test identity partition.
	 *
	 * @param promptTests the planned or retained prompt_test identities
	 * @return an eligible result or every cross-pool collision
	 */
	public static Result validate(List<Identity> promptTests)
	{
		if (promptTests == null)
			throw new NullPointerException("promptTests must not be null");
		for (Identity promptTest : promptTests)
		{
			if (promptTest == null)
				throw new NullPointerException("promptTests must not contain null elements");
		}

		List<Collision> collisions = new ArrayList<>();
		detect(promptTests, IdentityField.INSTANCE_IDENTIFIER, Identity::instanceIdentifier, collisions);
		detect(promptTests, IdentityField.SEED, Identity::seed, collisions);
		detect(promptTests, IdentityField.GENERATED_CONTENT_FINGERPRINT, Identity::generatedContentFingerprint,
			collisions);
		detect(promptTests, IdentityField.EXECUTION_IDENTIFIER, Identity::executionIdentifier, collisions);
		collisions.sort(Comparator.comparing(Collision::field).thenComparing(Collision::value).
			thenComparing(Collision::firstInstanceIdentifier).thenComparing(Collision::secondInstanceIdentifier));
		if (collisions.isEmpty())
			return new Result(Status.ELIGIBLE, List.of());
		return new Result(Status.BLOCKED, collisions);
	}

	/**
	 * Detects reuse of one identity field across distinct pools.
	 *
	 * @param promptTests the promptTests
	 * @param field the checked field
	 * @param valueExtractor the field accessor
	 * @param collisions the collision accumulator
	 */
	private static void detect(List<Identity> promptTests, IdentityField field, Function<Identity, String> valueExtractor,
		List<Collision> collisions)
	{
		Map<String, Identity> firstByValue = new HashMap<>();
		for (Identity promptTest : promptTests)
		{
			String value = valueExtractor.apply(promptTest);
			Identity first = firstByValue.putIfAbsent(value, promptTest);
			if (first != null && first.pool() != promptTest.pool())
			{
				collisions.add(new Collision(field, value, first.pool(), promptTest.pool(), first.instanceIdentifier(),
					promptTest.instanceIdentifier()));
			}
		}
	}

	/**
	 * Names one prompt_test evidence pool.
	 */
	public enum Pool
	{
		DEVELOPMENT,
		READINESS,
		FORMAL
	}

	/**
	 * Names an identity field that cannot cross pools.
	 */
	public enum IdentityField
	{
		INSTANCE_IDENTIFIER,
		SEED,
		GENERATED_CONTENT_FINGERPRINT,
		EXECUTION_IDENTIFIER
	}

	/**
	 * Names whether the partition may execute.
	 */
	public enum Status
	{
		ELIGIBLE,
		BLOCKED
	}

	/**
	 * Holds one prompt_test's immutable evidence identities.
	 *
	 * @param pool the evidence pool
	 * @param instanceIdentifier the generated prompt_test instance identifier
	 * @param seed the random-seed identity
	 * @param generatedContentFingerprint the lowercase SHA-256 generated-content fingerprint
	 * @param executionIdentifier the retained execution identifier
	 */
	public record Identity(Pool pool, String instanceIdentifier, String seed, String generatedContentFingerprint,
		String executionIdentifier)
	{
		/**
		 * Validates one prompt_test identity.
		 *
		 * @param pool the evidence pool
		 * @param instanceIdentifier the generated prompt_test instance identifier
		 * @param seed the random-seed identity
		 * @param generatedContentFingerprint the lowercase SHA-256 generated-content fingerprint
		 * @param executionIdentifier the retained execution identifier
		 */
		public Identity
		{
			if (pool == null)
				throw new NullPointerException("pool must not be null");
			instanceIdentifier = requireText(instanceIdentifier, "instanceIdentifier");
			seed = requireText(seed, "seed");
			generatedContentFingerprint = requireText(generatedContentFingerprint,
				"generatedContentFingerprint");
			if (!generatedContentFingerprint.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("generatedContentFingerprint must be a lowercase SHA-256 value");
			executionIdentifier = requireText(executionIdentifier, "executionIdentifier");
		}
	}

	/**
	 * Describes one cross-pool identity collision.
	 *
	 * @param field the reused identity field
	 * @param value the reused value
	 * @param firstPool the first evidence pool
	 * @param secondPool the later evidence pool
	 * @param firstInstanceIdentifier the first prompt_test instance
	 * @param secondInstanceIdentifier the later prompt_test instance
	 */
	public record Collision(IdentityField field, String value, Pool firstPool, Pool secondPool,
		String firstInstanceIdentifier, String secondInstanceIdentifier)
	{
		/**
		 * Validates one collision.
		 *
		 * @param field the reused identity field
		 * @param value the reused value
		 * @param firstPool the first evidence pool
		 * @param secondPool the later evidence pool
		 * @param firstInstanceIdentifier the first prompt_test instance
		 * @param secondInstanceIdentifier the later prompt_test instance
		 */
		public Collision
		{
			if (field == null || firstPool == null || secondPool == null)
				throw new NullPointerException("Collision enum fields must not be null");
			value = requireText(value, "value");
			firstInstanceIdentifier = requireText(firstInstanceIdentifier, "firstInstanceIdentifier");
			secondInstanceIdentifier = requireText(secondInstanceIdentifier, "secondInstanceIdentifier");
		}
	}

	/**
	 * Holds one partition result.
	 *
	 * @param status whether execution is eligible
	 * @param collisions every cross-pool collision
	 */
	public record Result(Status status, List<Collision> collisions)
	{
		/**
		 * Validates and snapshots one partition result.
		 *
		 * @param status whether execution is eligible
		 * @param collisions every cross-pool collision
		 */
		public Result
		{
			if (status == null)
				throw new NullPointerException("status must not be null");
			collisions = List.copyOf(collisions);
		}
	}

	/**
	 * Validates open text.
	 *
	 * @param value the value
	 * @param name the parameter name
	 * @return the unchanged value
	 */
	private static String requireText(String value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
