package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * One certification attempt's outcome and whether it still describes the current prompt system and conditions.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param index the certification index
 * @param outcome the attempt's outcome label, or {@code null} while it runs
 * @param historical {@code true} if CAT knows of a material change since the attempt measured its candidate
 * @param changed the names of the changed conditions, including {@code PROMPT_CONTENT} if the snapshot differs
 */
public record AttemptSummary(int index, String outcome, boolean historical, List<String> changed)
{
	/**
	 * Creates a summary.
	 *
	 * @param index the certification index
	 * @param outcome the attempt's outcome label, or {@code null} while it runs
	 * @param historical {@code true} if CAT knows of a material change since the attempt measured its candidate
	 * @param changed the names of the changed conditions
	 * @throws NullPointerException if {@code changed} is null
	 * @throws IllegalArgumentException if {@code index} is not positive
	 */
	public AttemptSummary
	{
		requireThat(index, "index").isPositive();
		requireThat(changed, "changed").isNotNull();
		changed = List.copyOf(changed);
	}
}
