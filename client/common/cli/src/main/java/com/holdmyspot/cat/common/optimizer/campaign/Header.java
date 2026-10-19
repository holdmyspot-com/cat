package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * A campaign's identity, project and prompt target, the user's goal for the target, starting harness, and lifecycle
 * state.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param id the campaign's identity
 * @param root the canonical project root
 * @param target the canonical path of the prompt file or prompt system
 * @param harness the harness that started the campaign
 * @param lifecycle indicates whether the campaign is active, cancelled, closed or finished
 * @param goal what the user wants the target to achieve, in the user's own words, or {@code null} if the user stated
 *   no goal
 */
public record Header(String id, Path root, Path target, Harness harness, Lifecycle lifecycle, String goal)
{
	/**
	 * Creates a header.
	 *
	 * @param id the campaign's identity
	 * @param root the canonical project root
	 * @param target the canonical path of the prompt file or prompt system
	 * @param harness the harness that started the campaign
	 * @param lifecycle whether the campaign is active, cancelled, closed or finished
	 * @param goal what the user wants the target to achieve, in the user's own words, or {@code null} if the user
	 *   stated no goal
	 * @throws NullPointerException if any argument other than {@code goal} is null
	 * @throws IllegalArgumentException if {@code id} or {@code goal} are blank
	 */
	public Header
	{
		requireThat(id, "id").isNotBlank();
		requireThat(root, "root").isNotNull();
		requireThat(target, "target").isNotNull();
		requireThat(harness, "harness").isNotNull();
		requireThat(lifecycle, "lifecycle").isNotNull();
		if (goal != null)
			requireThat(goal, "goal").isNotBlank();
	}

	/**
	 * Creates a header for a campaign whose user stated no goal.
	 *
	 * @param id the campaign's identity
	 * @param root the canonical project root
	 * @param target the canonical path of the prompt file or prompt system
	 * @param harness the harness that started the campaign
	 * @param lifecycle indicates whether the campaign is active, cancelled, closed or finished
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code id} is blank
	 */
	public Header(String id, Path root, Path target, Harness harness, Lifecycle lifecycle)
	{
		this(id, root, target, harness, lifecycle, null);
	}

	/**
	 * Returns this header with another lifecycle state.
	 *
	 * @param state the new state
	 * @return the updated header
	 * @throws NullPointerException if {@code state} is null
	 */
	public Header withLifecycle(Lifecycle state)
	{
		return new Header(id, root, target, harness, state, goal);
	}
}
