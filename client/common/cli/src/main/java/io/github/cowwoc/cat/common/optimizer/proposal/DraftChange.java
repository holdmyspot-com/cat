package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * How a prompt test in a draft directory relates to the prompt tests that the project records.
 *
 * @param kind whether the prompt test is recorded unchanged, created, or recorded and edited
 * @param diff the unified difference from the recorded files to the draft's files, or an empty string unless
 *   {@code kind} is {@link Kind#EDITED}
 */
public record DraftChange(Kind kind, String diff)
{
	/**
	 * How a prompt test in a draft directory relates to the recorded prompt tests.
	 */
	public enum Kind
	{
		/**
		 * The draft holds a recorded prompt test unchanged.
		 */
		RECORDED,
		/**
		 * The draft holds a prompt test that the project does not record.
		 */
		CREATED,
		/**
		 * The draft holds a recorded prompt test with changes, or converts a scenario in an earlier format.
		 */
		EDITED
	}

	/**
	 * Creates a change.
	 *
	 * @param kind whether the prompt test is recorded unchanged, created, or recorded and edited
	 * @param diff the unified difference from the recorded files to the draft's files, or an empty string unless
	 *   {@code kind} is {@link Kind#EDITED}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code diff} is empty for an edited prompt test or not empty for another
	 */
	public DraftChange
	{
		requireThat(kind, "kind").isNotNull();
		requireThat(diff, "diff").isNotNull();
		requireThat(diff.isEmpty(), "diff.isEmpty()").isEqualTo(kind != Kind.EDITED);
	}
}
