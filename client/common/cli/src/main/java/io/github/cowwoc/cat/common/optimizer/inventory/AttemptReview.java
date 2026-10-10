package io.github.cowwoc.cat.common.optimizer.inventory;

import java.util.List;

/**
 * The validation of one prompt author attempt's changes against its edit authority.
 * <p>
 * The attempt is admitted only if no operation is forbidden. Any forbidden operation rejects the whole attempt: it
 * contributes no candidate, even if some of its changes are permitted.
 *
 * @param forbidden every forbidden operation, in path order
 * @param changes the permitted changes to resolved targets, in target order
 */
public record AttemptReview(List<ForbiddenOperation> forbidden, List<TargetChange> changes)
{
	/**
	 * Creates a review.
	 *
	 * @param forbidden every forbidden operation, in path order
	 * @param changes the permitted changes to resolved targets, in target order
	 * @throws NullPointerException if any of the arguments are null or contain null
	 */
	public AttemptReview
	{
		forbidden = List.copyOf(forbidden);
		changes = List.copyOf(changes);
	}

	/**
	 * Indicates whether the attempt performed no forbidden operation, so its changes may form a candidate.
	 *
	 * @return {@code true} if no operation is forbidden
	 */
	public boolean isPermitted()
	{
		return forbidden.isEmpty();
	}
}
