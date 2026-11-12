package com.holdmyspot.cat.common.optimizer.campaign;

import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The entry that one delivery appended to its target's certification history: the certification record of a
 * certified delivery, or a labelled delivery entry for any other delivery.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe.
 */
public sealed interface HistoryEntry permits CertificationRecord, DeliveryEntry
{
	/**
	 * Returns the delivery's target.
	 *
	 * @return the target, relative to the project
	 */
	Path target();

	/**
	 * Returns the delivery's outcome.
	 *
	 * @return the label
	 */
	DeliveryLabel label();

	/**
	 * Returns the content hash of each delivered prompt file.
	 *
	 * @return the hashes, keyed by the file's path relative to the project
	 */
	Map<Path, Sha256> promptFiles();

	/**
	 * Returns the measurement conditions of the delivery.
	 *
	 * @return the conditions
	 */
	MeasurementIdentity identity();

	/**
	 * Returns the harness that the entry's conditions were measured in.
	 *
	 * @return the model provider that the measurement conditions record, which names the harness, or empty if they
	 *   record none
	 */
	default Optional<String> harness()
	{
		return Optional.ofNullable(identity().values().get(MeasurementCondition.PROVIDER));
	}
}
