package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * Offers the highest-ranked attempt that meets {@link KeepImprovement}'s conditions under one set of measurement
 * conditions. Keeping its prompt snapshot ends optimization with the label {@code improved, not certified}.
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
