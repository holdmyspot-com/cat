package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * The best qualifying certification attempt of one measurement identity, offered as its own {@code keep improvement}
 * choice.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param attempt the attempt's certification index
 * @param identity the measurement identity that the attempt was measured under
 * @param historical {@code true} if the identity is not the current one
 * @param differing the conditions whose values differ from the current identity, including conditions that only one
 *   of them records
 */
public record ImprovementOffer(int attempt, MeasurementIdentity identity, boolean historical,
	List<MeasurementCondition> differing)
{
	/**
	 * Creates an offer.
	 *
	 * @param attempt the attempt's certification index
	 * @param identity the measurement identity that the attempt was measured under
	 * @param historical {@code true} if the identity is not the current one
	 * @param differing the conditions whose values differ from the current identity
	 * @throws NullPointerException if {@code identity} or {@code differing} are null
	 * @throws IllegalArgumentException if {@code attempt} is not positive
	 */
	public ImprovementOffer
	{
		requireThat(attempt, "attempt").isPositive();
		requireThat(identity, "identity").isNotNull();
		requireThat(differing, "differing").isNotNull();
		differing = List.copyOf(differing);
	}
}
