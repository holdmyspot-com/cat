package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.RecordStore;

import java.io.IOException;
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
 * Reads and writes the strict versioned policy that controls development comparisons and readiness.
 */
public final class DevelopmentPolicyDocument
{
	private static final int MAXIMUM_DOCUMENT_BYTES = 64 * 1024;
	private static final String SCHEMA_VERSION = "development-policy-v1";
	private static final Set<String> FIELDS = Set.of("schema_version", "identifier", "initial_pairs",
		"additional_pairs", "maximum_pairs", "maximum_survivors", "promotion_net_wins", "material_regression_net_losses",
		"readiness_trials", "readiness_maximum_failures", "plateau_rounds", "plateau_minimum_improvement");

	/**
	 * Prevents construction of this static document boundary.
	 */
	private DevelopmentPolicyDocument()
	{
	}

	/**
	 * Atomically writes one complete development policy.
	 *
	 * @param json the JSON codec
	 * @param destination the destination document
	 * @param policy the executable policy
	 * @throws IOException if serialization or atomic persistence fails
	 */
	public static void write(JsonMapper json, Path destination, DevelopmentPolicy policy) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(destination, "destination must not be null");
		Objects.requireNonNull(policy, "policy must not be null");
		try
		{
			String contents = json.writerWithDefaultPrettyPrinter().writeValueAsString(document(json, policy));
			RecordStore.writeText(destination, contents + System.lineSeparator());
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot serialize development policy: " + destination, e);
		}
	}

	/**
	 * Reads one complete strict development policy.
	 *
	 * @param json the JSON codec
	 * @param source the source document
	 * @return the executable policy
	 * @throws IOException if the document is malformed, unsupported, or exceeds its bound
	 */
	public static DevelopmentPolicy read(JsonMapper json, Path source) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(source, "source must not be null");
		String contents = BoundedInput.readUtf8(source, MAXIMUM_DOCUMENT_BYTES, "Development policy");
		try
		{
			JsonMapper strict = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
			JsonNode parsed = strict.readTree(contents);
			if (!(parsed instanceof ObjectNode document))
				throw new IOException("Development policy is not a JSON object: " + source);
			if (!new HashSet<>(document.propertyNames()).equals(FIELDS))
				throw new IOException("Development policy has unsupported or missing fields: " + source);
			String schemaVersion = text(document, "schema_version", source);
			if (!schemaVersion.equals(SCHEMA_VERSION))
				throw new IOException("Unsupported development policy schema " + schemaVersion + ": " + source);
			return new DevelopmentPolicy(text(document, "identifier", source),
				positiveInteger(document, "initial_pairs", source),
				positiveInteger(document, "additional_pairs", source),
				positiveInteger(document, "maximum_pairs", source),
				positiveInteger(document, "maximum_survivors", source),
				positiveInteger(document, "promotion_net_wins", source),
				positiveInteger(document, "material_regression_net_losses", source),
				positiveInteger(document, "readiness_trials", source),
				nonnegativeInteger(document, "readiness_maximum_failures", source),
				positiveInteger(document, "plateau_rounds", source),
				probability(document, "plateau_minimum_improvement", source));
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("Invalid development policy JSON at " + source + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Creates the complete canonical field projection.
	 *
	 * @param json the JSON codec
	 * @param policy the policy
	 * @return the JSON object
	 */
	private static ObjectNode document(JsonMapper json, DevelopmentPolicy policy)
	{
		ObjectNode result = json.createObjectNode();
		result.put("schema_version", SCHEMA_VERSION);
		result.put("identifier", policy.identifier());
		result.put("initial_pairs", policy.initialPairs());
		result.put("additional_pairs", policy.additionalPairs());
		result.put("maximum_pairs", policy.maximumPairs());
		result.put("maximum_survivors", policy.maximumSurvivors());
		result.put("promotion_net_wins", policy.promotionNetWins());
		result.put("material_regression_net_losses", policy.materialRegressionNetLosses());
		result.put("readiness_trials", policy.readinessTrials());
		result.put("readiness_maximum_failures", policy.readinessMaximumFailures());
		result.put("plateau_rounds", policy.plateauRounds());
		result.put("plateau_minimum_improvement", policy.plateauMinimumImprovement());
		return result;
	}

	/**
	 * Reads one nonblank text field.
	 *
	 * @param document the policy document
	 * @param field the field name
	 * @param source the source path
	 * @return the text value
	 * @throws IOException if the value is absent or invalid
	 */
	private static String text(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Development policy has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Reads one positive integer field.
	 *
	 * @param document the policy document
	 * @param field the field name
	 * @param source the source path
	 * @return the positive integer
	 * @throws IOException if the value is invalid
	 */
	private static int positiveInteger(ObjectNode document, String field, Path source) throws IOException
	{
		int value = nonnegativeInteger(document, field, source);
		if (value < 1)
			throw new IOException("Development policy has invalid " + field + ": " + source);
		return value;
	}

	/**
	 * Reads one nonnegative integer field.
	 *
	 * @param document the policy document
	 * @param field the field name
	 * @param source the source path
	 * @return the nonnegative integer
	 * @throws IOException if the value is invalid
	 */
	private static int nonnegativeInteger(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0)
			throw new IOException("Development policy has invalid " + field + ": " + source);
		return value.asInt();
	}

	/**
	 * Reads one finite probability strictly between zero and one.
	 *
	 * @param document the policy document
	 * @param field the field name
	 * @param source the source path
	 * @return the probability
	 * @throws IOException if the value is invalid
	 */
	private static double probability(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isNumber())
			throw new IOException("Development policy has invalid " + field + ": " + source);
		double result = value.asDouble();
		if (!Double.isFinite(result) || result <= 0 || result >= 1)
			throw new IOException("Development policy has invalid " + field + ": " + source);
		return result;
	}
}
