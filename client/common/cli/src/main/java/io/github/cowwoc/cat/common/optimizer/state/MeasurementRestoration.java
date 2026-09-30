package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Retains the observation that restored a paused confirmed measurement epoch.
 *
 * @param schemaVersion supported restoration schema
 * @param campaignId owning campaign
 * @param lineageId confirmed lineage
 * @param epochRef restored measurement epoch
 * @param changeRef known change whose pause was resolved
 * @param restoredRef complete before-call observation under restored conditions
 * @param comparison comparison with the original accepted baseline
 */
public record MeasurementRestoration(int schemaVersion, String campaignId, String lineageId,
	EvidenceRef epochRef, EvidenceRef changeRef, EvidenceRef restoredRef,
	MeasurementConditions.Comparison comparison)
{
	/** Supported restoration schema. */
	public static final int SCHEMA_VERSION = 1;

	/**
	 * Validates restoration identity and evidence collections.
	 *
	 * @param schemaVersion supported restoration schema
	 * @param campaignId owning campaign
	 * @param lineageId confirmed lineage
	 * @param epochRef restored measurement epoch
	 * @param changeRef known change whose pause was resolved
	 * @param restoredRef complete before-call observation under restored conditions
	 * @param comparison comparison with the original accepted baseline
	 */
	public MeasurementRestoration
	{
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			lineageId == null || lineageId.isBlank())
			throw new IllegalArgumentException("Measurement restoration identity is invalid");
		Objects.requireNonNull(epochRef, "epochRef");
		Objects.requireNonNull(changeRef, "changeRef");
		Objects.requireNonNull(restoredRef, "restoredRef");
		Objects.requireNonNull(comparison, "comparison");
		if (!epochRef.collection().equals("measurement-epochs") ||
			!changeRef.collection().equals("measurement-changes") ||
			!restoredRef.collection().equals("dynamic-baseline-roots") || comparison.hasKnownChange())
			throw new IllegalArgumentException("Restoration requires an unchanged known baseline");
	}
}
