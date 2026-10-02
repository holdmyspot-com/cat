package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

/**
 * Tracks a round of prompt edits: each failing cell (category and trial profile) receives up to {@value #ATTEMPTS}
 * author attempts. Once every cell uses that allowance without promotion, development reaches a plateau.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param attempts the completed author attempts of each failing cell, in contract order
 */
public record Round(Map<String, Integer> attempts)
{
	/**
	 * The maximum author attempts a failing cell receives before the round moves to the next cell.
	 */
	public static final int ATTEMPTS = 2;

	/**
	 * Creates a round.
	 *
	 * @param attempts the completed author attempts of each failing cell, in contract order
	 * @throws NullPointerException if {@code attempts} is null
	 * @throws IllegalArgumentException if {@code attempts} is empty or a count is outside [0, {@value #ATTEMPTS}]
	 */
	public Round
	{
		requireThat(attempts, "attempts").isNotEmpty();
		for (Entry<String, Integer> entry : attempts.entrySet())
			requireThat(entry.getValue(), "attempts[" + entry.getKey() + "]").isBetween(0, true, ATTEMPTS, true);
		attempts = Collections.unmodifiableMap(new LinkedHashMap<>(attempts));
	}

	/**
	 * Returns the first cell that has author attempts left.
	 *
	 * @return the cell's identifier, or empty if the round is complete
	 */
	public Optional<String> nextCategory()
	{
		for (Entry<String, Integer> entry : attempts.entrySet())
		{
			if (entry.getValue() < ATTEMPTS)
				return Optional.of(entry.getKey());
		}
		return Optional.empty();
	}
}
