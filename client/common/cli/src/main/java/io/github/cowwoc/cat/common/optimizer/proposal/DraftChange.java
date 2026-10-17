package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ValuesFile;

/**
 * How a prompt test in a draft directory relates to the prompt tests that the project records.
 *
 * @param kind whether the prompt test is recorded unchanged, created, or recorded and edited
 * @param diff the unified difference from the recorded files to the draft's files other than the test's
 *   {@value ValuesFile#NAME}, or an empty string unless {@code kind} is {@link Kind#EDITED}
 * @param valuesChanged {@code true} if the draft changes the test's {@value ValuesFile#NAME}, whose values are the
 *   test's test cases
 */
public record DraftChange(Kind kind, String diff, boolean valuesChanged)
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
	 * @param diff the unified difference from the recorded files to the draft's files other than the test's
	 *   {@value ValuesFile#NAME}, or an empty string unless {@code kind} is {@link Kind#EDITED}
	 * @param valuesChanged {@code true} if the draft changes the test's {@value ValuesFile#NAME}, whose values are
	 *   the test's test cases
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code kind} is {@link Kind#EDITED} while {@code diff} is empty and
	 *   {@code valuesChanged} is {@code false}, or another kind while {@code diff} is not empty or
	 *   {@code valuesChanged} is {@code true}
	 */
	public DraftChange
	{
		requireThat(kind, "kind").isNotNull();
		requireThat(diff, "diff").isNotNull();
		boolean hasChanges = !diff.isEmpty() || valuesChanged;
		requireThat(hasChanges, "hasChanges").isEqualTo(kind == Kind.EDITED);
	}
}
