package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * A campaign's identity, project and prompt target, starting harness, and lifecycle state.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param id the campaign's identity
 * @param root the canonical project root
 * @param target the canonical path of the prompt file or prompt system
 * @param harness the harness that started the campaign
 * @param lifecycle whether the campaign is active, cancelled, closed or finished
 */
public record Header(String id, Path root, Path target, Harness harness, Lifecycle lifecycle)
{
	/**
	 * Creates a header.
	 *
	 * @param id the campaign's identity
	 * @param root the canonical project root
	 * @param target the canonical path of the prompt file or prompt system
	 * @param harness the harness that started the campaign
	 * @param lifecycle whether the campaign is active, cancelled, closed or finished
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code id} is blank
	 */
	public Header
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
	public Header withLifecycle(Lifecycle state)
	{
		return new Header(id, root, target, harness, state);
	}
}
