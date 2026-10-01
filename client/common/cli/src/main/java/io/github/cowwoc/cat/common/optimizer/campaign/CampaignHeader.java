package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * What identifies a campaign and binds it to a project, a target and a harness.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param id the campaign's identity
 * @param root the canonical project root
 * @param target the canonical path of the prompt file or prompt system
 * @param harness the harness that started the campaign
 * @param lifecycle where the campaign is in its life
 */
public record CampaignHeader(String id, Path root, Path target, Harness harness, Lifecycle lifecycle)
{
	/**
	 * Creates a header.
	 *
	 * @param id the campaign's identity
	 * @param root the canonical project root
	 * @param target the canonical path of the prompt file or prompt system
	 * @param harness the harness that started the campaign
	 * @param lifecycle where the campaign is in its life
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code id} is blank
	 */
	public CampaignHeader
	{
		requireThat(id, "id").isNotBlank();
		requireThat(root, "root").isNotNull();
		requireThat(target, "target").isNotNull();
		requireThat(harness, "harness").isNotNull();
		requireThat(lifecycle, "lifecycle").isNotNull();
	}

	/**
	 * Returns this header with another lifecycle state.
	 *
	 * @param state the new state
	 * @return the updated header
	 * @throws NullPointerException if {@code state} is null
	 */
	public CampaignHeader withLifecycle(Lifecycle state)
	{
		return new CampaignHeader(id, root, target, harness, state);
	}
}
