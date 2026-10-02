package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

/**
 * A round's frozen author policy, reserved sessions and received edits. Weak rounds address each failing category
 * once and validate all contenders before selecting one winner.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param attempts the received weak attempts or rejected sequential attempts per addressed cell, in contract order
 * @param weak whether the working candidate was weak when the round started
 * @param reservations the attempt numbers and addressed cells, in launch order
 * @param validationAttempts the received attempts, in launch order; an unchanged snapshot denotes no edit
 */
public record Round(Map<String, Integer> attempts, boolean weak, Map<Integer, String> reservations,
	List<AuthorAttempt> validationAttempts)
{
	/**
	 * The maximum attempts of a sequential round for each failing cell.
	 */
	public static final int ATTEMPTS = 2;
	private static final int WEAK_ATTEMPTS = 3;

	/**
	 * Creates a round.
	 *
	 * @param attempts the completed attempt counts
	 * @param weak whether this round uses concurrent attempts
	 * @param reservations the reserved attempts in launch order
	 * @param validationAttempts the received attempts
	 * @throws NullPointerException if a required value or element is null
	 * @throws IllegalArgumentException if a count, reservation or received attempt is invalid
	 */
	public Round
	{
		requireThat(attempts, "attempts").isNotEmpty();
		int limit = ATTEMPTS;
		if (weak)
			limit = WEAK_ATTEMPTS;
		for (Entry<String, Integer> entry : attempts.entrySet())
		{
			requireThat(entry.getKey(), "cell").isNotBlank();
			requireThat(entry.getValue(), "attempts[" + entry.getKey() + "]").isBetween(0, true, limit, true);
		}
		attempts = Collections.unmodifiableMap(new LinkedHashMap<>(attempts));
		reservations = Collections.unmodifiableMap(new LinkedHashMap<>(reservations));
		int previous = 0;
		Map<String, Integer> reserved = new LinkedHashMap<>();
		for (Entry<Integer, String> entry : reservations.entrySet())
		{
			requireThat(entry.getKey(), "attemptNumber").isGreaterThan(previous);
			requireThat(attempts.containsKey(entry.getValue()), "addressedCell").isTrue();
			reserved.merge(entry.getValue(), 1, Integer::sum);
			requireThat(reserved.get(entry.getValue()), "reservedAttempts").isLessThanOrEqualTo(limit);
			previous = entry.getKey();
		}
		validationAttempts = List.copyOf(validationAttempts);
		previous = 0;
		for (AuthorAttempt attempt : validationAttempts)
		{
			requireThat(attempt.number(), "attemptNumber").isGreaterThan(previous);
			requireThat(attempt.category(), "cell").isEqualTo(reservations.get(attempt.number()));
			previous = attempt.number();
		}
	}

	/**
	 * Creates an explicitly sequential round with no sessions reserved.
	 *
	 * @param attempts the completed counts
	 */
	public Round(Map<String, Integer> attempts)
	{
		this(attempts, false, Map.of(), List.of());
	}

	/**
	 * Returns the number of attempts each addressed cell receives.
	 *
	 * @return three for a weak round, otherwise two
	 */
	public int maxAttempts()
	{
		if (weak)
			return WEAK_ATTEMPTS;
		return ATTEMPTS;
	}

	/**
	 * Returns the first addressed cell with an unreserved attempt slot.
	 *
	 * @return the cell identifier, or empty once all slots are reserved
	 */
	public Optional<String> nextCategory()
	{
		if (!weak && !pendingAuthorAttempts().isEmpty())
		{
			return Optional.empty();
		}
		for (Entry<String, Integer> entry : attempts.entrySet())
		{
			long used = entry.getValue();
			if (weak)
				used = reservations.values().stream().filter(entry.getKey()::equals).count();
			if (used < maxAttempts())
				return Optional.of(entry.getKey());
		}
		return Optional.empty();
	}

	/**
	 * Returns reserved sessions whose edits have not been received, in launch order.
	 *
	 * @return the pending attempt numbers and cells
	 */
	public Map<Integer, String> pendingAuthorAttempts()
	{
		Map<Integer, String> pending = new LinkedHashMap<>(reservations);
		for (AuthorAttempt attempt : validationAttempts)
			pending.remove(attempt.number());
		return Collections.unmodifiableMap(pending);
	}

	/**
	 * Returns this round's identity, the first reserved attempt number.
	 *
	 * @return the round number, or zero before its first session is reserved
	 */
	public int number()
	{
		return reservations.keySet().stream().findFirst().orElse(0);
	}

	/**
	 * Reserves a session before the driver launches it.
	 *
	 * @param number the campaign's next author attempt number
	 * @param category the addressed cell
	 * @return the updated round
	 */
	Round reserve(int number, String category)
	{
		Map<Integer, String> updated = new LinkedHashMap<>(reservations);
		updated.put(number, category);
		return new Round(attempts, weak, updated, validationAttempts);
	}

	/**
	 * Inserts or updates a received attempt while preserving launch order.
	 *
	 * @param attempt the received or updated attempt
	 * @return the updated round
	 */
	Round withAttempt(AuthorAttempt attempt)
	{
		List<AuthorAttempt> updated = new ArrayList<>(validationAttempts);
		boolean received = updated.removeIf(existing -> existing.number() == attempt.number());
		updated.add(attempt);
		updated.sort(Comparator.comparingInt(AuthorAttempt::number));
		Map<String, Integer> counts = new LinkedHashMap<>(attempts);
		if (weak && !received)
			counts.merge(attempt.category(), 1, Integer::sum);
		return new Round(counts, weak, reservations, updated);
	}
}
