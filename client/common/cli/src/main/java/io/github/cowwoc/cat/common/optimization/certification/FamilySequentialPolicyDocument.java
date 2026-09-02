package io.github.cowwoc.cat.common.optimization.certification;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.Fingerprints;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the strict versioned policy that controls family-sequential certification.
 */
public final class FamilySequentialPolicyDocument
{
	private static final int MAXIMUM_DOCUMENT_BYTES = 64 * 1024;
	private static final String SCHEMA_VERSION = "family-sequential-policy-v1";
	private static final Set<String> FIELDS = Set.of("schema_version", "identifier", "statistical_revision",
		"target", "maximum_trials_per_family", "lineage_error_budget", "candidate_allowance_schedule");

	/**
	 * Reads and fingerprints one complete policy document.
	 *
	 * @param json the JSON codec
	 * @param source the policy document
	 * @return the typed policy and canonical fingerprint
	 * @throws IOException if the document is malformed, unsupported, or exceeds its bound
	 */
	public static Loaded read(JsonMapper json, Path source) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(source, "source must not be null");
		String contents = BoundedInput.readUtf8(source, MAXIMUM_DOCUMENT_BYTES, "Family-sequential policy");
		try
		{
			JsonMapper strictJson = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
			JsonNode parsed = strictJson.readTree(contents);
			if (!(parsed instanceof ObjectNode document))
				throw new IOException("Family-sequential policy is not a JSON object: " + source);
			if (!new HashSet<>(document.propertyNames()).equals(FIELDS))
				throw new IOException("Family-sequential policy has unsupported or missing fields: " + source);
			String schemaVersion = requiredText(document, "schema_version", source);
			if (!schemaVersion.equals(SCHEMA_VERSION))
				throw new IOException("Unsupported family-sequential policy schema " + schemaVersion + ": " + source);
			String identifier = requiredText(document, "identifier", source);
			String statisticalRevision = requiredText(document, "statistical_revision", source);
			BigDecimal target = decimalText(document, "target", source);
			int maximumTrials = positiveInteger(document, "maximum_trials_per_family", source);
			BigDecimal lineageBudget = decimalText(document, "lineage_error_budget", source);
			CandidateAllowanceLedger.Schedule schedule = CandidateAllowanceLedger.Schedule.parse(
				requiredText(document, "candidate_allowance_schedule", source));
			FamilySequentialPolicy policy = new FamilySequentialPolicy(identifier, statisticalRevision,
				target.doubleValue(), maximumTrials, lineageBudget, schedule);
			ObjectNode canonical = canonical(json, policy);
			return new Loaded(policy, Fingerprints.ofUtf8(json.writeValueAsString(canonical)));
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("Invalid family-sequential policy JSON at " + source + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Produces the canonical policy identity.
	 *
	 * @param json the JSON codec
	 * @param policy the typed policy
	 * @return the canonical JSON object
	 */
	private static ObjectNode canonical(JsonMapper json, FamilySequentialPolicy policy)
	{
		ObjectNode result = json.createObjectNode();
		result.put("schema_version", SCHEMA_VERSION);
		result.put("identifier", policy.identifier());
		result.put("statistical_revision", policy.statisticalRevision());
		result.put("target", BigDecimal.valueOf(policy.decisionPolicy().target()).stripTrailingZeros().toPlainString());
		result.put("maximum_trials_per_family", policy.decisionPolicy().maximumTrialsPerFamily());
		result.put("lineage_error_budget", policy.lineageErrorBudget().stripTrailingZeros().toPlainString());
		result.put("candidate_allowance_schedule", policy.candidateAllowanceSchedule().externalValue());
		return result;
	}

	/**
	 * Reads one required nonblank text field.
	 *
	 * @param document the policy object
	 * @param field the field name
	 * @param source the source path
	 * @return the text
	 * @throws IOException if the value is not nonblank text
	 */
	private static String requiredText(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Family-sequential policy has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Reads an exact decimal encoded as text.
	 *
	 * @param document the policy object
	 * @param field the field name
	 * @param source the source path
	 * @return the decimal
	 * @throws IOException if the value is not canonical decimal text
	 */
	private static BigDecimal decimalText(ObjectNode document, String field, Path source) throws IOException
	{
		String value = requiredText(document, field, source);
		try
		{
			BigDecimal result = new BigDecimal(value);
			if (!result.stripTrailingZeros().toPlainString().equals(value))
				throw new IOException("Family-sequential policy has noncanonical " + field + ": " + source);
			return result;
		}
		catch (NumberFormatException e)
		{
			throw new IOException("Family-sequential policy has invalid " + field + ": " + source, e);
		}
	}

	/**
	 * Reads one positive integer field.
	 *
	 * @param document the policy object
	 * @param field the field name
	 * @param source the source path
	 * @return the positive integer
	 * @throws IOException if the value is not a positive integer
	 */
	private static int positiveInteger(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1)
			throw new IOException("Family-sequential policy has invalid " + field + ": " + source);
		return value.asInt();
	}

	/**
	 * Holds one parsed policy and the fingerprint of its canonical representation.
	 *
	 * @param policy the typed policy
	 * @param fingerprint the canonical SHA-256 fingerprint
	 */
	public record Loaded(FamilySequentialPolicy policy, String fingerprint)
	{
		/**
		 * Validates one loaded policy.
		 *
		 * @param policy the typed policy
		 * @param fingerprint the canonical SHA-256 fingerprint
		 */
		public Loaded
		{
			Objects.requireNonNull(policy, "policy must not be null");
			Objects.requireNonNull(fingerprint, "fingerprint must not be null");
			if (!fingerprint.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("fingerprint must be a lowercase SHA-256 value");
		}
	}

	/**
	 * Prevents construction of this static document boundary.
	 */
	private FamilySequentialPolicyDocument()
	{
	}
}
