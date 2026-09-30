package io.github.cowwoc.cat.common.optimizer.candidate;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Exact final observation of one transaction path.
 *
 * @param target project-relative user-visible path
 * @param before predecessor file state
 * @param after selected file state
 * @param observedFinal final inspected state
 * @param outcome path-specific outcome
 */
public record DeliveryPathReceipt(Path target, DeliveryFileState before, DeliveryFileState after,
	DeliveryFileState observedFinal, Outcome outcome)
{
	/** Validates one immutable path receipt. */
	public DeliveryPathReceipt
	{
		DeliveryPath.validateRelative(target);
		Objects.requireNonNull(before, "before");
		Objects.requireNonNull(after, "after");
		Objects.requireNonNull(observedFinal, "observedFinal");
		Objects.requireNonNull(outcome, "outcome");
		if (outcome == Outcome.APPLIED && !after.equals(observedFinal) ||
			outcome == Outcome.ROLLED_BACK && !before.equals(observedFinal))
			throw new IllegalArgumentException("Delivery path receipt outcome differs from final state");
	}

	/** Path-specific delivery outcome. */
	public enum Outcome
	{
		APPLIED,
		ROLLED_BACK,
		CONCURRENT_EDIT_PRESERVED
	}
}
