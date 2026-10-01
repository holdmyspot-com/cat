package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.List;

/**
 * The persisted progress of a campaign's delivery: the candidate it delivers, the changes that the preflight planned,
 * and how many of them are known to be applied.
 * <p>
 * Delivery writes the journal before it changes any file and records each change after applying it, so a restart
 * resumes the same delivery instead of planning it again.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param candidate the identity of the delivered candidate
 * @param changes the planned changes, in the order they are applied
 * @param applied the number of leading changes known to be applied
 */
public record DeliveryJournal(Sha256 candidate, List<DeliveryChange> changes, int applied)
{
	/**
	 * Creates a journal.
	 *
	 * @param candidate the identity of the delivered candidate
	 * @param changes the planned changes, in the order they are applied
	 * @param applied the number of leading changes known to be applied
	 * @throws NullPointerException if {@code candidate} or {@code changes} are null, or if {@code changes} contains
	 *   null
	 * @throws IllegalArgumentException if {@code applied} is negative or exceeds the number of changes, or if a
	 *   change's sequence differs from its position in the list
	 */
	public DeliveryJournal
	{
		requireThat(candidate, "candidate").isNotNull();
		requireThat(changes, "changes").isNotNull();
		changes = List.copyOf(changes);
		for (int i = 0; i < changes.size(); ++i)
			requireThat(changes.get(i).sequence(), "changes[" + i + "].sequence()").isEqualTo(i);
		requireThat(applied, "applied").isBetween(0, true, changes.size(), true);
	}

	/**
	 * Returns this journal with more changes applied.
	 *
	 * @param applied the number of leading changes known to be applied
	 * @return the updated journal
	 * @throws IllegalArgumentException if {@code applied} is negative or exceeds the number of changes
	 */
	public DeliveryJournal withApplied(int applied)
	{
		return new DeliveryJournal(candidate, changes, applied);
	}

	/**
	 * Indicates whether every planned change is known to be applied.
	 *
	 * @return {@code true} if {@code applied} equals the number of changes
	 */
	public boolean isComplete()
	{
		return applied == changes.size();
	}
}
