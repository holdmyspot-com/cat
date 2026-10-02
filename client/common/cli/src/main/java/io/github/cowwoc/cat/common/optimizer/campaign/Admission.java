package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

/**
 * A trial session that {@link Scheduler} plans: assign a new trial or rerun an assigned one.
 */
public sealed interface Admission
{
	/**
	 * Returns why the trial runs.
	 *
	 * @return the purpose
	 */
	Purpose purpose();

	/**
	 * Returns the digest of the prompt-system snapshot that the trial runs.
	 *
	 * @return the snapshot
	 */
	Sha256 snapshot();

	/**
	 * Returns the trial's cell identifier: its requirement category and runner profile.
	 *
	 * @return the category
	 */
	String category();

	/**
	 * Reruns an assigned trial with the same instance, purpose and snapshot.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param key the trial
	 */
	record Rerun(TrialKey key) implements Admission
	{
		/**
		 * Creates the admission.
		 *
		 * @param key the trial
		 * @throws NullPointerException if {@code key} is null
		 */
		public Rerun
		{
			requireThat(key, "key").isNotNull();
		}

		@Override
		public Purpose purpose()
		{
			return key.purpose();
		}

		@Override
		public Sha256 snapshot()
		{
			return key.snapshot();
		}

		@Override
		public String category()
		{
			return key.category();
		}
	}

	/**
	 * Assigns a new trial instance to a purpose and snapshot. Recording {@link CampaignEvent.Assign} precedes
	 * starting its session.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param purpose why the trial runs
	 * @param series the series that the trial belongs to, as {@link TrialKey#series()} describes
	 * @param snapshot the snapshot that the trial runs
	 * @param category the trial's cell identifier
	 * @param instance the targeted instance, or {@code null} if the assignment draws a representative instance
	 * @param allocation the allocation decision that chose the trial
	 */
	record Assign(Purpose purpose, int series, Sha256 snapshot, String category, TrialInstance instance,
		Allocation allocation) implements Admission
	{
		/**
		 * Creates the admission.
		 *
		 * @param purpose why the trial runs
		 * @param series the series that the trial belongs to
		 * @param snapshot the snapshot that the trial runs
		 * @param category the trial's non-blank cell identifier
		 * @param instance the targeted instance, or {@code null} if the assignment draws a representative instance
		 * @param allocation the allocation decision that chose the trial
		 * @throws NullPointerException if {@code purpose}, {@code snapshot}, {@code category} or {@code allocation}
		 *   are null
		 * @throws IllegalArgumentException if {@code series} is negative, {@code category} is blank,
		 *   {@code instance} is missing for a targeted purpose or supplied for a representative purpose,
		 *   {@code instance} is not marked targeted or belongs to another cell, or the allocation rule does not assign
		 *   the purpose
		 */
		public Assign
		{
			requireThat(purpose, "purpose").isNotNull();
			requireThat(series, "series").isNotNegative();
			requireThat(snapshot, "snapshot").isNotNull();
			requireThat(category, "category").isNotBlank();
			requireThat(allocation, "allocation").isNotNull();
			requireThat(allocation.rule().assigns(purpose), "allocation.rule().assigns(purpose)").isTrue();
			boolean targeted = instance != null;
			requireThat(targeted, "targeted").isNotEqualTo(purpose.isRepresentative(), "purpose.isRepresentative()");
			if (instance != null)
			{
				requireThat(instance.targeted(), "instance.targeted()").isTrue();
				requireThat(instance.cellId(), "instance.cellId()").isEqualTo(category, "category");
			}
		}
	}
}
