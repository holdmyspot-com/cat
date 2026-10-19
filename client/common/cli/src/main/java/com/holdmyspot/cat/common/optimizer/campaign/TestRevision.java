package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileDelta;
import com.holdmyspot.cat.common.optimizer.inventory.UnifiedDiff;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * A change that a test revision made to one prompt test.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param lineage the number of the lineage whose test the revision changed
 * @param test the identifier of the test
 * @param before the test's scenario before the revision
 * @param after the test's scenario after the revision
 * @param reasons why the revision changed the test
 */
public record TestRevision(int lineage, String test, String before, String after, String reasons)
{
	/**
	 * Creates a test revision.
	 *
	 * @param lineage the number of the lineage whose test the revision changed
	 * @param test the identifier of the test
	 * @param before the test's scenario before the revision
	 * @param after the test's scenario after the revision
	 * @param reasons why the revision changed the test
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code lineage} is not positive, or {@code test} or {@code reasons} is
	 *   blank
	 */
	public TestRevision
	{
		requireThat(lineage, "lineage").isPositive();
		requireThat(test, "test").isNotBlank();
		requireThat(before, "before").isNotNull();
		requireThat(after, "after").isNotNull();
		requireThat(reasons, "reasons").isNotBlank();
	}

	/**
	 * Returns the revision as a unified diff of the test's scenario, labeled with the test identifier.
	 *
	 * @return the diff, empty if the scenario did not change
	 */
	public String diff()
	{
		return UnifiedDiff.of(List.of(new FileDelta(Path.of(test), new FileContent(before.getBytes(UTF_8)),
			new FileContent(after.getBytes(UTF_8)), Set.of())));
	}
}
