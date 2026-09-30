package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical durable form of one complete point-in-time condition observation.
 *
 * @param schemaVersion supported observation schema
 * @param campaignId owning campaign
 * @param lineageId confirmed lineage
 * @param epochRef active measurement epoch
 * @param logicalCallId call whose boundary was observed
 * @param stage observation boundary relative to that call
 * @param fields one known or explicitly unknown value for each material condition
 */
public record MeasurementObservation(int schemaVersion, String campaignId, String lineageId,
	EvidenceRef epochRef, String logicalCallId, Stage stage, List<Field> fields)
{
	/** Supported durable observation schema. */
	public static final int SCHEMA_VERSION = 1;

	/** The call boundary at which conditions were observed. */
	public enum Stage
	{
		BEFORE_CALL,
		AFTER_CALL
	}

	/** Known evidence or an explicit observation limitation. */
	public enum ValueKind
	{
		KNOWN,
		UNKNOWN
	}

	/**
	 * One serializable condition value with an explicit finite disposition.
	 *
	 * @param kind material condition dimension
	 * @param valueKind known or unknown
	 * @param evidenceRef immutable evidence when known
	 * @param limitation reason when unknown
	 */
	public record Field(MeasurementConditions.Kind kind, ValueKind valueKind, EvidenceRef evidenceRef,
		String limitation)
	{
		/** Validates the one-of known evidence and unknown limitation. */
		public Field
		{
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(valueKind, "valueKind");
			if (valueKind == ValueKind.KNOWN && (evidenceRef == null || limitation != null) ||
				valueKind == ValueKind.UNKNOWN && (evidenceRef != null || limitation == null ||
					limitation.isBlank()))
				throw new IllegalArgumentException("Condition field must have exactly one known value or limitation");
		}
	}

	/** Validates a complete, ordered observation graph. */
	public MeasurementObservation
	{
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			lineageId == null || lineageId.isBlank() || logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("Measurement observation identity is invalid");
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(epochRef, "epochRef");
		if (!epochRef.collection().equals("measurement-epochs"))
			throw new IllegalArgumentException("Measurement observation has a foreign epoch");
		fields = List.copyOf(fields);
		MeasurementConditions.Kind[] kinds = MeasurementConditions.Kind.values();
		if (fields.size() != kinds.length)
			throw new IllegalArgumentException("Measurement observation requires every material condition");
		for (int index = 0; index < kinds.length; index += 1)
			if (fields.get(index).kind() != kinds[index])
				throw new IllegalArgumentException("Measurement observation fields must use canonical condition order");
	}

	/**
	 * Converts complete domain values into the canonical durable representation.
	 *
	 * @param campaignId owning campaign
	 * @param lineageId confirmed lineage
	 * @param epochRef active epoch
	 * @param logicalCallId call whose conditions were observed
	 * @param stage observation boundary
 * @param conditions complete observed conditions
	 * @return canonical observation
	 */
	public static MeasurementObservation from(String campaignId, String lineageId, EvidenceRef epochRef,
		String logicalCallId, Stage stage, MeasurementConditions conditions)
	{
		return new MeasurementObservation(SCHEMA_VERSION, campaignId, lineageId, epochRef, logicalCallId,
			stage, fieldsFrom(conditions));
	}

	/**
	 * Converts complete conditions to the ordered durable field representation.
	 *
	 * @param conditions known and explicitly limited conditions
	 * @return ordered immutable field values
	 */
	static List<Field> fieldsFrom(MeasurementConditions conditions)
	{
		Objects.requireNonNull(conditions, "conditions");
		List<Field> fields = new ArrayList<>();
		for (MeasurementConditions.Kind kind : MeasurementConditions.Kind.values())
		{
			MeasurementConditions.Value value = conditions.values().get(kind);
			if (value instanceof MeasurementConditions.Value.Known known)
				fields.add(new Field(kind, ValueKind.KNOWN, known.evidence(), null));
			else if (value instanceof MeasurementConditions.Value.Unknown unknown)
				fields.add(new Field(kind, ValueKind.UNKNOWN, null, unknown.limitation()));
			else
				throw new IllegalArgumentException("Unsupported measurement condition value: " + kind);
		}
		return List.copyOf(fields);
	}

	/**
	 * Derives one index key for a confirmed call's boundary observation.
	 *
	 * @param epochRef active measurement epoch
	 * @param logicalCallId exact logical call identity
	 * @return stable per-call key within the selected boundary index collection
	 */
	public static String callIndexKey(EvidenceRef epochRef, String logicalCallId)
	{
		Objects.requireNonNull(epochRef, "epochRef");
		if (!epochRef.collection().equals("measurement-epochs") ||
			logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("Call observation key requires an epoch and call identity");
		return epochRef.evidenceId() + ":" + logicalCallId;
	}

	/**
	 * Restores complete domain values from a validated durable observation.
	 *
	 * @return comparable material conditions
	 */
	public MeasurementConditions conditions()
	{
		return conditionsFrom(fields);
	}

	/**
	 * Restores domain values from a complete canonical field list.
	 *
	 * @param fields complete ordered field list
	 * @return comparable conditions
	 */
	static MeasurementConditions conditionsFrom(List<Field> fields)
	{
		Map<MeasurementConditions.Kind, MeasurementConditions.Value> values =
			new EnumMap<>(MeasurementConditions.Kind.class);
		for (Field field : fields)
		{
			MeasurementConditions.Value value;
			if (field.valueKind() == ValueKind.KNOWN)
				value = new MeasurementConditions.Value.Known(field.evidenceRef());
			else
				value = new MeasurementConditions.Value.Unknown(field.limitation());
			values.put(field.kind(), value);
		}
		return new MeasurementConditions(values);
	}
}
