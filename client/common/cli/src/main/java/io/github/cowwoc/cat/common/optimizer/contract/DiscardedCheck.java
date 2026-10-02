package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A proposed check that failed one of its controls. CAT keeps it in the contract so that the proposal can say why
 * its requirement is judged semantically (design &sect;6.1).
 *
 * @param check the check
 * @param reason which control it failed, and what it observed
 */
public record DiscardedCheck(DeterministicCheck check, String reason)
{
	/**
	 * Creates a discarded check.
	 *
	 * @param check the check
	 * @param reason which control it failed, and what it observed
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code reason} is blank
	 */
	public DiscardedCheck
	{
		requireThat(check, "check").isNotNull();
		requireThat(reason, "reason").isNotBlank();
	}
}
