package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

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
	 * Returns the trial's cell identifier: its prompt test and runner profile.
	 *
	 * @return the test
	 */
	String cell();

	/**
	 * Reruns an assigned trial with the same test case, purpose and snapshot.
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
		public String cell()
		{
			return key.cell();
		}
	}

	/**
	 * Assigns a new test case to a purpose and snapshot. Recording {@link CampaignEvent.Assign} precedes
	 * starting its session.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param purpose why the trial runs
	 * @param series the series that the trial belongs to, as {@link TrialKey#series()} describes
	 * @param snapshot the snapshot that the trial runs
	 * @param cell the trial's cell identifier
	 * @param testCase the replayed test case, or {@code null} if the assignment draws the test case
	 * @param allocation the allocation decision that chose the trial
	 */
	record Assign(Purpose purpose, int series, Sha256 snapshot, String cell, DrawnTestCase testCase,
		Allocation allocation) implements Admission
	{
		/**
		 * Creates the admission.
		 *
		 * @param purpose why the trial runs
		 * @param series the series that the trial belongs to
		 * @param snapshot the snapshot that the trial runs
		 * @param cell the trial's non-blank cell identifier
		 * @param testCase the replayed test case, or {@code null} if the assignment draws the test case
		 * @param allocation the allocation decision that chose the trial
		 * @throws NullPointerException if {@code purpose}, {@code snapshot}, {@code cell} or {@code allocation}
		 *   are null
		 * @throws IllegalArgumentException if {@code series} is negative, {@code cell} is blank,
		 *   {@code testCase} is missing for a purpose that replays a test case or supplied for one that draws it,
		 *   {@code testCase} belongs to another cell, a development purpose's {@code testCase} is not marked
		 *   targeted, a {@link Purpose#CONTROL control} trial's held-out {@code testCase} is marked targeted, or the
		 *   allocation rule does not assign the purpose
		 */
		public Assign
		{
			requireThat(purpose, "purpose").isNotNull();
			requireThat(series, "series").isNotNegative();
			requireThat(snapshot, "snapshot").isNotNull();
			requireThat(cell, "cell").isNotBlank();
			requireThat(allocation, "allocation").isNotNull();
			requireThat(allocation.rule().assigns(purpose), "allocation.rule().assigns(purpose)").isTrue();
			boolean replaysTestCase = testCase != null;
			requireThat(replaysTestCase, "replaysTestCase").
				isNotEqualTo(purpose.drawsAtAssignment(), "purpose.drawsAtAssignment()");
			if (testCase != null)
			{
				requireThat(testCase.targeted(), "testCase.targeted()").
					isEqualTo(purpose.part() == Part.DEVELOPMENT, "developmentPurpose");
				requireThat(testCase.cellId(), "testCase.cellId()").isEqualTo(cell, "cell");
			}
		}
	}
}
