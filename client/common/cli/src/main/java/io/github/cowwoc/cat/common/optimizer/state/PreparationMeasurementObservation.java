package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.List;
import java.util.Objects;

/**
 * Complete condition observation owned by an unconfirmed contract preparation call.
 *
 * @param schemaVersion supported observation schema
 * @param campaignId owning campaign
 * @param cycleRef indexed preparation cycle
 * @param ownerRef indexed inspection owner
 * @param logicalCallId call whose boundary was observed
 * @param stage boundary relative to the call
 * @param fields known values or explicit limitations for every material condition
 */
public record PreparationMeasurementObservation(int schemaVersion, String campaignId,
	EvidenceRef cycleRef, EvidenceRef ownerRef, String logicalCallId, MeasurementObservation.Stage stage,
	List<MeasurementObservation.Field> fields)
{
	/** Supported preparation observation schema. */
	public static final int SCHEMA_VERSION = 1;

	/**
	 * Validates the preparation identity and complete canonical field order.
	 *
	 * @param schemaVersion supported observation schema
	 * @param campaignId owning campaign
	 * @param cycleRef indexed preparation cycle
	 * @param ownerRef indexed inspection owner
	 * @param logicalCallId call whose boundary was observed
	 * @param stage boundary relative to the call
	 * @param fields known values or explicit limitations for every material condition
	 */
	public PreparationMeasurementObservation
	{
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("Preparation observation identity is invalid");
		Objects.requireNonNull(cycleRef, "cycleRef");
		Objects.requireNonNull(ownerRef, "ownerRef");
		Objects.requireNonNull(stage, "stage");
		if (!cycleRef.collection().equals("contract-preparation-cycles") ||
			!ownerRef.collection().equals("contract-preparation-owners"))
			throw new IllegalArgumentException("Preparation observation has foreign authority");
		fields = List.copyOf(fields);
		MeasurementConditions.Kind[] kinds = MeasurementConditions.Kind.values();
		if (fields.size() != kinds.length)
			throw new IllegalArgumentException("Preparation observation requires every material condition");
		for (int index = 0; index < kinds.length; index += 1)
			if (fields.get(index).kind() != kinds[index])
				throw new IllegalArgumentException("Preparation observation fields are out of order");
	}

	/**
	 * Creates a complete observation from domain condition values.
	 *
	 * @param campaignId owning campaign
	 * @param cycleRef indexed preparation cycle
	 * @param ownerRef indexed inspection owner
	 * @param logicalCallId call whose boundary was observed
	 * @param stage boundary relative to the call
	 * @param conditions complete observed values
	 * @return canonical observation
	 */
	public static PreparationMeasurementObservation from(String campaignId, EvidenceRef cycleRef,
		EvidenceRef ownerRef, String logicalCallId, MeasurementObservation.Stage stage,
		MeasurementConditions conditions)
	{
		return new PreparationMeasurementObservation(SCHEMA_VERSION, campaignId, cycleRef, ownerRef,
			logicalCallId, stage, MeasurementObservation.fieldsFrom(conditions));
	}

	/**
	 * Derives the stable key within the preconfirmation before-call observation namespace.
	 *
	 * @param cycleRef indexed preparation cycle
	 * @param logicalCallId exact logical call
	 * @return domain-specific call key
	 */
	public static String indexKey(EvidenceRef cycleRef, String logicalCallId)
	{
		if (cycleRef == null || !cycleRef.collection().equals("contract-preparation-cycles") ||
			logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("Preparation observation key requires a cycle and call");
		return cycleRef.evidenceId() + ":" + logicalCallId;
	}

	/**
	 * Restores complete comparable condition values.
	 *
	 * @return known values and explicit limitations
	 */
	public MeasurementConditions conditions()
	{
		return MeasurementObservation.conditionsFrom(fields);
	}
}
