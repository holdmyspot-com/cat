package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Retains a known condition difference that paused admission to another call.
 *
 * @param schemaVersion supported schema
 * @param campaignId owning campaign
 * @param lineageId confirmed lineage
 * @param epochRef active measurement epoch
 * @param baselineRef last accepted condition observation
 * @param changedRef condition observation that exposed the difference
 * @param comparison complete field-level decision
 */
public record MeasurementChange(int schemaVersion, String campaignId, String lineageId,
	EvidenceRef epochRef, EvidenceRef baselineRef, EvidenceRef changedRef,
	MeasurementConditions.Comparison comparison)
{
	/** Supported change record schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates the same-epoch comparison and its known change. */
	public MeasurementChange
	{
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			lineageId == null || lineageId.isBlank())
			throw new IllegalArgumentException("Measurement change identity is invalid");
		Objects.requireNonNull(epochRef, "epochRef");
		Objects.requireNonNull(baselineRef, "baselineRef");
		Objects.requireNonNull(changedRef, "changedRef");
		Objects.requireNonNull(comparison, "comparison");
		if (!epochRef.collection().equals("measurement-epochs") ||
			!baselineRef.collection().equals("dynamic-baseline-roots") ||
			!changedRef.collection().equals("dynamic-baseline-roots") ||
			!comparison.hasKnownChange())
			throw new IllegalArgumentException("Measurement pause requires a known same-epoch condition change");
	}
}
