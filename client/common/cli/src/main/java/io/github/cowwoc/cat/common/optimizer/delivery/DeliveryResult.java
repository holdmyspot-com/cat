package io.github.cowwoc.cat.common.optimizer.delivery;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryConflict;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange;

import java.nio.file.Path;
import java.util.List;

/**
 * The result of one {@link Delivery#deliver delivery} run. Only {@link Delivered} reports a complete delivery; every
 * other result leaves the delivery unfinished and says why.
 * <p>
 * The certification label of the delivered candidate and the delivery status are reported separately: the label is
 * part of {@link Delivered}, and the result type is the delivery status.
 */
public sealed interface DeliveryResult
{
	/**
	 * Every change of the candidate is in place in the base worktree and the external targets, and verified.
	 *
	 * @param label the certification label that the delivered result carries
	 * @param applicability {@code HISTORICAL} if an edit made only in the base worktree or the external targets changed a
	 *                      prompt source that the candidate does not change, so that the merged prompt system is a
	 *                      different snapshot than the one measured; otherwise {@code CURRENT}
	 * @param changedPromptSources the prompt-source paths that only the base or external state changed, in path order
	 * @param baseOnlyChanges every path that only the base or external state changed since the fork; delivery left them
	 *                        unchanged
	 * @param changes the changes that delivered the candidate, in the order they were applied
	 * @param diff the Git diff of the delivered paths in the base worktree against its {@code HEAD}, including created
	 *             files; empty if the candidate changes only external targets
	 */
	record Delivered(Outcome label, Applicability applicability, List<Path> changedPromptSources,
		List<Path> baseOnlyChanges, List<DeliveryChange> changes, String diff) implements DeliveryResult
	{
		/**
		 * Creates a result.
		 *
		 * @param label the certification label that the delivered result carries
		 * @param applicability {@code HISTORICAL} if an edit made only in the base worktree or the external targets
		 *                      changed a prompt source that the candidate does not change; otherwise {@code CURRENT}
		 * @param changedPromptSources the prompt-source paths that only the base or external state changed
		 * @param baseOnlyChanges every path that only the base or external state changed since the fork
		 * @param changes the changes that delivered the candidate, in the order they were applied
		 * @param diff the Git diff of the delivered paths in the base worktree against its {@code HEAD}
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Delivered
		{
			requireThat(label, "label").isNotNull();
			requireThat(applicability, "applicability").isNotNull();
			changedPromptSources = List.copyOf(changedPromptSources);
			baseOnlyChanges = List.copyOf(baseOnlyChanges);
			changes = List.copyOf(changes);
			requireThat(diff, "diff").isNotNull();
		}

		/**
		 * Returns the external change record: the delivered changes to files outside the base worktree.
		 *
		 * @return the changes whose target is absolute, in the order they were applied
		 */
		public List<DeliveryChange> externalChanges()
		{
			return changes.stream().filter(change -> change.target().isAbsolute()).toList();
		}
	}

	/**
	 * The preflight found conflicts between the candidate and the current base or external state. Delivery stopped
	 * before writing any file or journal; the user decides how to proceed.
	 *
	 * @param conflicts the conflicts, grouped by the candidate's target in path order
	 */
	record Conflicted(List<DeliveryConflict> conflicts) implements DeliveryResult
	{
		/**
		 * Creates a result.
		 *
		 * @param conflicts the conflicts, grouped by the candidate's target in path order
		 * @throws NullPointerException if {@code conflicts} is null
		 */
		public Conflicted
		{
			conflicts = List.copyOf(conflicts);
		}
	}

	/**
	 * The optimization worktree holds files that neither the working candidate nor the delivered candidate records.
	 * Delivery stopped before changing any file in the base worktree or the external targets, and did not incorporate
	 * the change; the user decides how to proceed.
	 *
	 * @param changes the differences between the working candidate and the optimization worktree, in path order
	 */
	record UnownedChange(List<PathChange> changes) implements DeliveryResult
	{
		/**
		 * Creates a result.
		 *
		 * @param changes the differences between the working candidate and the optimization worktree
		 * @throws NullPointerException if {@code changes} is null
		 */
		public UnownedChange
		{
			changes = List.copyOf(changes);
		}
	}

	/**
	 * A target changed after the delivery was journaled: it matches neither the state the change expects before it
	 * nor the state the change leaves. Delivery stopped without overwriting it. The changes before it remain applied,
	 * and running the delivery again resumes from this change once the target is restored.
	 *
	 * @param change the change that was not applied
	 * @param applied the number of leading changes that are applied
	 */
	record ConcurrentEdit(DeliveryChange change, int applied) implements DeliveryResult
	{
		/**
		 * Creates a result.
		 *
		 * @param change the change that was not applied
		 * @param applied the number of leading changes that are applied
		 * @throws NullPointerException if {@code change} is null
		 * @throws IllegalArgumentException if {@code applied} is negative
		 */
		public ConcurrentEdit
		{
			requireThat(change, "change").isNotNull();
			requireThat(applied, "applied").isNotNegative();
		}
	}

	/**
	 * Every change was applied, but the final check found targets that no longer hold the candidate's state, for
	 * example because another process changed them afterwards. The delivery is not complete.
	 *
	 * @param mismatched the targets whose current state differs from the candidate, in the order they were applied
	 */
	record VerificationFailed(List<Path> mismatched) implements DeliveryResult
	{
		/**
		 * Creates a result.
		 *
		 * @param mismatched the targets whose current state differs from the candidate
		 * @throws NullPointerException if {@code mismatched} is null
		 */
		public VerificationFailed
		{
			mismatched = List.copyOf(mismatched);
		}
	}
}
