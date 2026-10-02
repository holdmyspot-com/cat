package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashSet;
import java.util.List;

/**
 * Requirements that no reply can satisfy together, as the deriver found them in the prompt system.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param requirementIds at least two distinct, non-blank requirement identifiers
 * @param evidence non-blank text identifying the file and instructions that show the conflict
 */
public record Conflict(List<String> requirementIds, String evidence)
{
	/**
	 * Creates a conflict.
	 *
	 * @param requirementIds at least two distinct, non-blank requirement identifiers
	 * @param evidence non-blank text identifying the file and instructions that show the conflict
	 * @throws NullPointerException if any of the arguments are null, or {@code requirementIds} contains null
	 * @throws IllegalArgumentException if {@code requirementIds} contains fewer than two identifiers, a duplicate or
	 *   a blank identifier
	 * @throws IllegalArgumentException if {@code evidence} is blank
	 */
	public Conflict
	{
		requirementIds = List.copyOf(requirementIds);
		requireThat(requirementIds.size(), "requirementIds.size()").isGreaterThanOrEqualTo(2);
		requireThat(new HashSet<>(requirementIds).size(), "distinctRequirementIds").
			isEqualTo(requirementIds.size());
		for (String id : requirementIds)
			requireThat(id, "requirementId").isNotBlank();
		requireThat(evidence, "evidence").isNotBlank();
	}
}
