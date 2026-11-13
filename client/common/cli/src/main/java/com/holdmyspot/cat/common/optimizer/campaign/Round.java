package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.secrets.RoundAccess;
import com.holdmyspot.cat.common.secrets.RoundSecrets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

/**
 * A round's frozen prompt author policy, reserved sessions and received edits. Weak rounds address each failing
 * test once and validate all contenders before selecting one winner.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param attempts the received weak attempts or rejected sequential attempts per addressed cell, in contract order
 * @param weak indicates whether the working candidate was weak when the round started
 * @param reservations the attempt numbers and addressed cells, in launch order
 * @param validationAttempts the received attempts, in launch order; an unchanged snapshot denotes no edit
 * @param approachOrders     frozen approach orders by addressed cell
 * @param rankApproaches     indicates whether unranked cells need a ranking session before reservation
 * @param rotationSize       the declaration-order vocabulary size for saved rounds without ranking
 * @param raceRound          indicates that accepted attempts will be evaluated as independent racers
 * @param attemptLimits      per-cell limits, including six for tests decided entirely by tools
 */
public record Round(Map<String, Integer> attempts, boolean weak, Map<Integer, String> reservations,
	List<PromptAuthorAttempt> validationAttempts, Map<String, ApproachOrder> approachOrders, boolean rankApproaches,
	int rotationSize, boolean raceRound, Map<String, Integer> attemptLimits)
{
	static
	{
		RoundSecrets.setRoundAccess(new RoundAccess()
		{
			@Override
			public Round reserve(Round round, int number, String cell)
			{
				return round.reserve(number, cell);
			}

			@Override
			public Round withAttempt(Round round, PromptAuthorAttempt attempt)
			{
				return round.withAttempt(attempt);
			}
		});
	}

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
	 * @param approachOrders     frozen orders by cell
	 * @param rankApproaches     indicates whether cells need ranking before reservation
	 * @param rotationSize       number of approaches in a saved declaration-order rotation
	 * @param raceRound          indicates that accepted attempts will be evaluated as independent racers
	 * @param attemptLimits      per-cell attempt limits
	 * @throws NullPointerException if a required value or element is null
	 * @throws IllegalArgumentException if a count, reservation or received attempt is invalid
	 */
	public Round
	{
		approachOrders = Map.copyOf(approachOrders);
		requireThat(attempts.keySet().containsAll(approachOrders.keySet()), "rankedCells").isTrue();
		requireThat(rotationSize, "rotationSize").isBetween(1, true, PromptAuthorApproach.values().length, true);
		if (raceRound)
			requireThat(weak, "weak").isTrue();
		requireThat(attemptLimits, "attemptLimits").isNotNull();
		requireThat(attempts.keySet().containsAll(attemptLimits.keySet()), "attemptLimitCells").isTrue();
		for (Entry<String, Integer> entry : attemptLimits.entrySet())
			requireThat(entry.getValue(), "attemptLimits[" + entry.getKey() + "]").isBetween(1, true, 6, true);
		attemptLimits = Map.copyOf(attemptLimits);
		requireThat(attempts, "attempts").isNotEmpty();
		for (Entry<String, Integer> entry : attempts.entrySet())
		{
			requireThat(entry.getKey(), "cell").isNotBlank();
			int limit = attemptLimit(weak, attemptLimits, entry.getKey());
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
			requireThat(reserved.get(entry.getValue()), "reservedAttempts").isLessThanOrEqualTo(
				attemptLimit(weak, attemptLimits, entry.getValue()));
			previous = entry.getKey();
		}
		validationAttempts = List.copyOf(validationAttempts);
		previous = 0;
		for (PromptAuthorAttempt attempt : validationAttempts)
		{
			requireThat(attempt.number(), "attemptNumber").isGreaterThan(previous);
			requireThat(attempt.cell(), "cell").isEqualTo(reservations.get(attempt.number()));
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
	 * Creates a round. A round with no reservations ranks each cell before reserving its attempts; an existing
	 * reservation without an order retains declaration-order assignment.
	 *
	 * @param attempts           completed counts per cell
	 * @param weak               indicates whether attempts may run concurrently
	 * @param reservations       reserved attempts in launch order
	 * @param validationAttempts received attempts
	 */
	public Round(Map<String, Integer> attempts, boolean weak, Map<Integer, String> reservations,
		List<PromptAuthorAttempt> validationAttempts)
	{
		this(attempts, weak, reservations, validationAttempts, Map.of(), reservations.isEmpty(),
			PromptAuthorApproach.values().length, false, Map.of());
	}

	/**
	 * Creates a round with its frozen approach orders.
	 *
	 * @param attempts completed counts per cell
	 * @param weak indicates whether attempts may run concurrently
	 * @param reservations reserved attempts in launch order
	 * @param validationAttempts received attempts
	 * @param approachOrders frozen orders by cell
	 * @param rankApproaches indicates whether cells need ranking before reservation
	 * @param rotationSize number of approaches in a saved declaration-order rotation
	 */
	public Round(Map<String, Integer> attempts, boolean weak, Map<Integer, String> reservations,
		List<PromptAuthorAttempt> validationAttempts, Map<String, ApproachOrder> approachOrders, boolean rankApproaches,
		int rotationSize)
	{
		this(attempts, weak, reservations, validationAttempts, approachOrders, rankApproaches, rotationSize, false,
			Map.of());
	}

	/** Creates a round with an explicit race flag and no tool-specific attempt limits. */
	public Round(Map<String, Integer> attempts, boolean weak, Map<Integer, String> reservations,
		List<PromptAuthorAttempt> validationAttempts, Map<String, ApproachOrder> approachOrders, boolean rankApproaches,
		int rotationSize, boolean raceRound)
	{
		this(attempts, weak, reservations, validationAttempts, approachOrders, rankApproaches, rotationSize, raceRound,
			Map.of());
	}

	/**
	 * Freezes one cell's order before any of its attempts are reserved.
	 *
	 * @param cell  the addressed cell
	 * @param order the ranked or fallback order
	 * @return the updated round
	 * @throws IllegalArgumentException if the cell is unknown, already ranked or already reserved
	 */
	public Round withApproachOrder(String cell, ApproachOrder order)
	{
		requireThat(attempts.containsKey(cell), "addressedCell").isTrue();
		requireThat(approachOrders.containsKey(cell), "alreadyRanked").isFalse();
		requireThat(reservations.containsValue(cell), "alreadyReserved").isFalse();
		Map<String, ApproachOrder> updated = new LinkedHashMap<>(approachOrders);
		updated.put(cell, order);
		return new Round(attempts, weak, reservations, validationAttempts, updated, rankApproaches, rotationSize,
		raceRound, attemptLimits);
	}

	/**
	 * Returns a concurrent round whose accepted attempts will be sampled as racers.
	 *
	 * @return the race round
	 */
	public Round asRaceRound()
	{
		return asRaceRound(Map.of());
	}

	/** Returns a race round whose tool-decided tests receive six independent author attempts. */
	public Round asRaceRound(Map<String, Integer> limits)
	{
		if (raceRound)
			return this;
		return new Round(attempts, true, reservations, validationAttempts, approachOrders, rankApproaches,
			rotationSize, true, limits);
	}

	/**
	 * Returns the number of attempts each addressed cell receives.
	 *
	 * @return the highest cell limit, three for an ordinary weak round or two for a sequential round
	 */
	public int maxAttempts()
	{
		int defaultLimit = ATTEMPTS;
		if (weak)
			defaultLimit = WEAK_ATTEMPTS;
		return attemptLimits.values().stream().mapToInt(Integer::intValue).max().orElse(defaultLimit);
	}

	/** Returns the attempt limit for a cell. */
	public int maxAttempts(String cell)
	{
		return attemptLimit(weak, attemptLimits, cell);
	}

	/**
	 * Indicates whether this round received all of its attempts. A complete round that promoted no attempt ends once
	 * each of its attempts is decided, and the working candidate resumes its representative trials.
	 *
	 * @return {@code true} if every addressed cell received all of its prompt author attempts and, in a weak round,
	 *   every reserved attempt was submitted
	 */
	public boolean isComplete()
	{
		for (Entry<String, Integer> entry : attempts.entrySet())
		{
			if (entry.getValue() < maxAttempts(entry.getKey()))
				return false;
		}
		return !weak || pendingPromptAuthorAttempts().isEmpty();
	}

	/**
	 * Returns the first addressed cell with an unreserved attempt slot.
	 *
	 * @return the cell identifier, or empty once all slots are reserved
	 */
	public Optional<String> nextCell()
	{
		if (!weak && !pendingPromptAuthorAttempts().isEmpty())
		{
			return Optional.empty();
		}
		for (Entry<String, Integer> entry : attempts.entrySet())
		{
			long used = entry.getValue();
			if (weak)
				used = reservations.values().stream().filter(entry.getKey()::equals).count();
			if (used < maxAttempts(entry.getKey()))
				return Optional.of(entry.getKey());
		}
		return Optional.empty();
	}

	/**
	 * Returns reserved sessions whose edits have not been received, in launch order.
	 *
	 * @return the pending attempt numbers and cells
	 */
	public Map<Integer, String> pendingPromptAuthorAttempts()
	{
		Map<Integer, String> pending = new LinkedHashMap<>(reservations);
		for (PromptAuthorAttempt attempt : validationAttempts)
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
	 * Returns the editing approach for a reserved attempt. A cell's attempts take its frozen order in sequence.
	 * A saved round without an order continues its declaration-order rotation from earlier rounds, using the
	 * vocabulary size recorded with that round.
	 *
	 * @param number the reserved attempt number
	 * @param earlier the number of prompt author attempts that addressed the same cell in earlier rounds
	 * @return the approach derived from durable reservation order, including for a resumed session
	 * @throws IllegalArgumentException if {@code number} is not reserved in this round or {@code earlier} is negative
	 */
	public PromptAuthorApproach approach(int number, int earlier)
	{
		requireThat(reservations.containsKey(number), "reservedAttempt").isTrue();
		requireThat(earlier, "earlier").isNotNegative();
		String cell = reservations.get(number);
		long position = reservations.entrySet().stream().
			filter(entry -> entry.getKey() <= number && entry.getValue().equals(cell)).count();
		PromptAuthorApproach[] approaches = PromptAuthorApproach.values();
		ApproachOrder order = approachOrders.get(cell);
		if (order != null)
			return order.approaches().get(Math.toIntExact(position - 1));
		return approaches[Math.toIntExact((earlier + position - 1) % rotationSize)];
	}

	/**
	 * Reserves a session before the driver launches it.
	 *
	 * @param number the campaign's next prompt author attempt number
	 * @param cell the addressed cell
	 * @return the updated round
	 */
	Round reserve(int number, String cell)
	{
		Map<Integer, String> updated = new LinkedHashMap<>(reservations);
		updated.put(number, cell);
		return new Round(attempts, weak, updated, validationAttempts, approachOrders, rankApproaches, rotationSize,
			raceRound, attemptLimits);
	}

	/**
	 * Inserts or updates a received attempt while preserving launch order.
	 *
	 * @param attempt the received or updated attempt
	 * @return the updated round
	 */
	Round withAttempt(PromptAuthorAttempt attempt)
	{
		List<PromptAuthorAttempt> updated = new ArrayList<>(validationAttempts);
		boolean received = updated.removeIf(existing -> existing.number() == attempt.number());
		updated.add(attempt);
		updated.sort(Comparator.comparingInt(PromptAuthorAttempt::number));
		Map<String, Integer> counts = new LinkedHashMap<>(attempts);
		if (weak && !received)
			counts.merge(attempt.cell(), 1, Integer::sum);
		return new Round(counts, weak, reservations, updated, approachOrders, rankApproaches, rotationSize, raceRound,
			attemptLimits);
	}

	/** Returns the applicable sequential or weak-round attempt limit for one cell. */
	private static int attemptLimit(boolean weak, Map<String, Integer> limits, String cell)
	{
		Integer limit = limits.get(cell);
		if (limit != null)
			return limit;
		if (weak)
			return WEAK_ATTEMPTS;
		return ATTEMPTS;
	}
}
