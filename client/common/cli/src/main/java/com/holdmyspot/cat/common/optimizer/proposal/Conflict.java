package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashSet;
import java.util.List;

/**
 * Requirements that no reply can satisfy together, as the contract author found them in the prompt system.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param requirementIds at least two distinct, non-blank requirement identifiers
 * @param evidence non-blank text identifying the file and instructions that show the conflict
 * @param resolutions at least two distinct, non-blank changes that resolve the conflict
 * @param recommended the 1-based position of the recommended resolution
 * @param reason non-blank reason for recommending that resolution
 */
public record Conflict(List<String> requirementIds, String evidence, List<String> resolutions, int recommended,
	String reason)
{
	/**
	 * Creates a conflict.
	 *
	 * @param requirementIds at least two distinct, non-blank requirement identifiers
	 * @param evidence non-blank text identifying the file and instructions that show the conflict
	 * @param resolutions at least two distinct, non-blank changes that resolve the conflict
	 * @param recommended the 1-based position of the recommended resolution
	 * @param reason non-blank reason for recommending that resolution
	 * @throws NullPointerException if any of the arguments are null, or either list contains null
	 * @throws IllegalArgumentException if {@code requirementIds} contains fewer than two identifiers, a duplicate or
	 *   a blank identifier
	 * @throws IllegalArgumentException if {@code evidence} is blank
	 * @throws IllegalArgumentException if {@code resolutions} contains fewer than two resolutions, a duplicate or
	 *   a blank resolution
	 * @throws IllegalArgumentException if {@code recommended} is outside the list of resolutions
	 * @throws IllegalArgumentException if {@code reason} is blank
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
		resolutions = List.copyOf(resolutions);
		requireThat(resolutions.size(), "resolutions.size()").isGreaterThanOrEqualTo(2);
		requireThat(new HashSet<>(resolutions).size(), "distinctResolutions").isEqualTo(resolutions.size());
		for (String resolution : resolutions)
			requireThat(resolution, "resolution").isNotBlank();
		requireThat(recommended, "recommended").isGreaterThanOrEqualTo(1).
			isLessThanOrEqualTo(resolutions.size());
		requireThat(reason, "reason").isNotBlank();
	}
}
