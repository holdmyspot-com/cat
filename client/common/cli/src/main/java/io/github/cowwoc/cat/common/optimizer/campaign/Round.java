package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

/**
 * A development round: each failing category receives up to {@value #ATTEMPTS} author attempts.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param attempts the completed author attempts of each failing category, in category order
 */
public record Round(Map<String, Integer> attempts)
{
	/**
	 * The author attempts that each failing category receives.
	 */
	public static final int ATTEMPTS = 2;

	/**
	 * Creates a round.
	 *
	 * @param attempts the completed author attempts of each failing category, in category order
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
	 * Returns the first category that has author attempts left.
	 *
	 * @return the category, or empty if the round is complete
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
