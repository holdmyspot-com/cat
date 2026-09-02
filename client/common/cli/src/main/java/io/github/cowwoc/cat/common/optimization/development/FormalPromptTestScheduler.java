package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.optimization.certification.FamilySequentialDecision;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Selects one frozen formal prompt_test from the statistical engine's requested family.
 */
public final class FormalPromptTestScheduler
{
	/**
	 * Selects and validates one formal prompt_test before model inference.
	 *
	 * @param decision the proof-covered statistical decision
	 * @param catalog the exact formal policy bound to every frozen family
	 * @param seed the immutable formal draw seed
	 * @param executionIdentifier the planned retained execution identifier
	 * @param knownIdentities all development, readiness, and prior formal prompt_test identities
	 * @return a scheduled prompt_test or every cross-pool collision
	 */
	public static Result schedule(FamilySequentialDecision.Result decision, PromptTestContractCatalog catalog, long seed,
		String executionIdentifier, List<PromptTestPoolPartition.Identity> knownIdentities)
	{
		Objects.requireNonNull(decision, "decision must not be null");
		Objects.requireNonNull(catalog, "catalog must not be null");
		executionIdentifier = requireText(executionIdentifier, "executionIdentifier");
		Objects.requireNonNull(knownIdentities, "knownIdentities must not be null");
		if (decision.action() != FamilySequentialDecision.Action.SAMPLE_FAMILY)
		{
			throw new IllegalArgumentException("Formal prompt_test requires SAMPLE_FAMILY, received " +
				decision.action());
		}

		String familyIdentifier = decision.familyId().orElseThrow();
		PromptTestGenerationPolicy policy = catalog.policy(familyIdentifier);
		if (policy.pool() != PromptTestPoolPartition.Pool.FORMAL)
		{
			throw new IllegalArgumentException("Frozen family is not bound to a formal prompt_test policy: " +
				familyIdentifier);
		}
		PromptTestGenerationPolicy.PromptTest promptTest = policy.generate(seed);
		PromptTestPoolPartition.Identity identity = PromptTestPoolPartition.createIdentity(promptTest,
			executionIdentifier);
		List<PromptTestPoolPartition.Identity> proposedIdentities = new ArrayList<>(knownIdentities);
		proposedIdentities.add(identity);
		PromptTestPoolPartition.Result partition = PromptTestPoolPartition.validate(proposedIdentities);
		if (partition.status() == PromptTestPoolPartition.Status.BLOCKED)
			return new Result(Status.BLOCKED, Optional.empty(), partition.collisions());
		return new Result(Status.SCHEDULED, Optional.of(new ScheduledPromptTest(promptTest, identity)), List.of());
	}

	/**
	 * Names whether a formal prompt_test may execute.
	 */
	public enum Status
	{
		SCHEDULED,
		BLOCKED
	}

	/**
	 * Holds one exact formal draw and its cross-pool identity.
	 *
	 * @param promptTest the generated formal prompt_test
	 * @param identity the identity retained before inference
	 */
	public record ScheduledPromptTest(PromptTestGenerationPolicy.PromptTest promptTest,
		PromptTestPoolPartition.Identity identity)
	{
		/**
		 * Validates one scheduled formal draw.
		 *
		 * @param promptTest the generated formal prompt_test
		 * @param identity the identity retained before inference
		 */
		public ScheduledPromptTest
		{
			Objects.requireNonNull(promptTest, "promptTest must not be null");
			Objects.requireNonNull(identity, "identity must not be null");
			if (promptTest.pool() != PromptTestPoolPartition.Pool.FORMAL ||
				identity.pool() != PromptTestPoolPartition.Pool.FORMAL)
			{
				throw new IllegalArgumentException("A scheduled formal prompt_test must belong to the formal evidence " +
					"pool");
			}
			if (!identity.instanceIdentifier().equals(promptTest.instanceIdentifier()) ||
				!identity.seed().equals(PromptTestPoolPartition.createIdentity(promptTest,
					identity.executionIdentifier()).seed()) ||
				!identity.generatedContentFingerprint().equals(promptTest.contentFingerprint()))
			{
				throw new IllegalArgumentException("Formal prompt_test identity does not match the generated prompt_test");
			}
		}
	}

	/**
	 * Holds a scheduled draw or the collisions that blocked it.
	 *
	 * @param status the scheduling result
	 * @param scheduledPromptTest the draw when scheduled
	 * @param collisions every cross-pool collision when blocked
	 */
	public record Result(Status status, Optional<ScheduledPromptTest> scheduledPromptTest,
		List<PromptTestPoolPartition.Collision> collisions)
	{
		/**
		 * Validates one scheduling result.
		 *
		 * @param status the scheduling result
		 * @param scheduledPromptTest the draw when scheduled
		 * @param collisions every cross-pool collision when blocked
		 */
		public Result
		{
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(scheduledPromptTest, "scheduledPromptTest must not be null");
			collisions = List.copyOf(collisions);
			if ((status == Status.SCHEDULED) != scheduledPromptTest.isPresent())
				throw new IllegalArgumentException("Only a scheduled result must contain a prompt_test");
			if ((status == Status.BLOCKED) != !collisions.isEmpty())
				throw new IllegalArgumentException("Only a blocked result must contain collisions");
		}
	}

	/**
	 * Validates required open text.
	 *
	 * @param value the proposed text
	 * @param name the parameter name
	 * @return the unchanged text
	 */
	private static String requireText(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Prevents construction of this static scheduling owner.
	 */
	private FormalPromptTestScheduler()
	{
	}
}
