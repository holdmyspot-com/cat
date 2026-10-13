package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A test revision's report that one prompt test needs no change.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param lineage the number of the lineage whose test the report covers
 * @param test the identifier of the test
 * @param reasons why the test needs no change
 */
public record SoundTest(int lineage, String test, String reasons)
{
	/**
	 * Creates a report.
	 *
	 * @param lineage the number of the lineage whose test the report covers
	 * @param test the identifier of the test
	 * @param reasons why the test needs no change
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code lineage} is not positive, or {@code test} or {@code reasons} is
	 *   blank
	 */
	public SoundTest
	{
		requireThat(lineage, "lineage").isPositive();
		requireThat(test, "test").isNotBlank();
		requireThat(reasons, "reasons").isNotBlank();
	}
}
