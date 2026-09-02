package io.github.cowwoc.cat.common.optimization;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.optimization.cost.MoneyAmount;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the bounded, strict document used to render one optimization round.
 */
public final class OptimizationRoundReportDocument
{
	private static final int MAXIMUM_DOCUMENT_BYTES = 64 * 1024;
	private static final String SCHEMA_VERSION = "optimization-round-report-v1";
	private static final Set<String> ROOT_FIELDS = Set.of("schema_version", "current_lower_bound", "target",
		"best_validated_version", "usage", "evidence_kind", "limiting_families", "next_action");
	private static final Set<String> USAGE_FIELDS = Set.of("provider_cost", "input_tokens", "output_tokens",
		"elapsed_nanos", "operations");
	private static final Set<String> COST_FIELDS = Set.of("amount", "currency");
	private static final Set<String> FAMILY_FIELDS = Set.of("name", "formal_trials", "failures", "status");
	private static final Set<String> ACTION_FIELDS = Set.of("action", "work", "reason");

	/**
	 * Reads one complete round-report document.
	 *
	 * @param json the JSON codec
	 * @param source the report document
	 * @return the typed report snapshot
	 * @throws IOException if the document is malformed, unsupported, or exceeds its size bound
	 */
	public static OptimizationRoundReport.Snapshot read(JsonMapper json, Path source) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(source, "source must not be null");
		String contents = BoundedInput.readUtf8(source, MAXIMUM_DOCUMENT_BYTES, "Optimization round report");
		try
		{
			JsonMapper strictJson = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
			JsonNode parsed = strictJson.readTree(contents);
			if (!(parsed instanceof ObjectNode document))
				throw new IOException("Optimization round report is not a JSON object: " + source);
			verifyFields(document, ROOT_FIELDS, "Optimization round report", source);
			String schemaVersion = requiredText(document, "schema_version", "Optimization round report", source);
			if (!schemaVersion.equals(SCHEMA_VERSION))
				throw new IOException("Unsupported optimization round report schema " + schemaVersion + ": " + source);

			OptionalDouble currentLowerBound = optionalProbability(document, "current_lower_bound", source);
			double target = probability(document, "target", source);
			String bestValidatedVersion =
				requiredText(document, "best_validated_version", "Optimization round report", source);
			OptimizationRoundReport.Usage usage = usage(requiredObject(document, "usage", source), source);
			OptimizationRoundReport.EvidenceKind evidenceKind = evidenceKind(
				requiredText(document, "evidence_kind", "Optimization round report", source));
			List<OptimizationRoundReport.LimitingFamily> limitingFamilies =
				limitingFamilies(requiredArray(document, "limiting_families", source), source);
			OptimizationRoundReport.NextAction nextAction =
				nextAction(requiredObject(document, "next_action", source), source);
			return new OptimizationRoundReport.Snapshot(currentLowerBound, target, bestValidatedVersion, usage,
				evidenceKind, limitingFamilies, nextAction);
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("Invalid optimization round report JSON at " + source + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Reads retained operation usage.
	 *
	 * @param document the usage object
	 * @param source the report document path
	 * @return the typed usage
	 * @throws IOException if the usage is malformed
	 */
	private static OptimizationRoundReport.Usage usage(ObjectNode document, Path source) throws IOException
	{
		verifyFields(document, USAGE_FIELDS, "Optimization round report usage", source);
		Optional<MoneyAmount> providerCost = providerCost(document.get("provider_cost"), source);
		long inputTokens = nonnegativeLong(document, "input_tokens", "usage", source);
		long outputTokens = nonnegativeLong(document, "output_tokens", "usage", source);
		long elapsedNanos = nonnegativeLong(document, "elapsed_nanos", "usage", source);
		int operations = nonnegativeInteger(document, "operations", "usage", source);
		return new OptimizationRoundReport.Usage(providerCost, inputTokens, outputTokens,
			Duration.ofNanos(elapsedNanos), operations);
	}

	/**
	 * Reads an optional exact provider cost.
	 *
	 * @param value the nullable provider-cost node
	 * @param source the report document path
	 * @return the exact cost, or empty when the provider did not supply one
	 * @throws IOException if the cost is malformed
	 */
	private static Optional<MoneyAmount> providerCost(JsonNode value, Path source) throws IOException
	{
		if (value == null)
			throw new IOException("Optimization round report usage has missing provider_cost: " + source);
		if (value.isNull())
			return Optional.empty();
		if (!(value instanceof ObjectNode cost))
			throw new IOException("Optimization round report usage has invalid provider_cost: " + source);
		verifyFields(cost, COST_FIELDS, "Optimization round report provider cost", source);
		String amountText = requiredText(cost, "amount", "Optimization round report provider cost", source);
		String currencyText = requiredText(cost, "currency", "Optimization round report provider cost", source);
		return Optional.of(new MoneyAmount(new BigDecimal(amountText), Currency.getInstance(currencyText)));
	}

	/**
	 * Reads every limiting-family diagnostic.
	 *
	 * @param values the family array
	 * @param source the report document path
	 * @return the typed diagnostics
	 * @throws IOException if a diagnostic is malformed
	 */
	private static List<OptimizationRoundReport.LimitingFamily> limitingFamilies(ArrayNode values, Path source)
		throws IOException
	{
		List<OptimizationRoundReport.LimitingFamily> result = new ArrayList<>(values.size());
		for (JsonNode value : values)
		{
			if (!(value instanceof ObjectNode family))
				throw new IOException("Optimization round report has a non-object limiting family: " + source);
			verifyFields(family, FAMILY_FIELDS, "Optimization round report limiting family", source);
			String name = requiredText(family, "name", "Optimization round report limiting family", source);
			int formalTrials = nonnegativeInteger(family, "formal_trials", "limiting family", source);
			int failures = nonnegativeInteger(family, "failures", "limiting family", source);
			OptimizationRoundReport.FamilyStatus status = familyStatus(
				requiredText(family, "status", "Optimization round report limiting family", source));
			result.add(new OptimizationRoundReport.LimitingFamily(name, formalTrials, failures, status));
		}
		return List.copyOf(result);
	}

	/**
	 * Reads the already-selected next action.
	 *
	 * @param document the next-action object
	 * @param source the report document path
	 * @return the typed action
	 * @throws IOException if the action is malformed
	 */
	private static OptimizationRoundReport.NextAction nextAction(ObjectNode document, Path source) throws IOException
	{
		verifyFields(document, ACTION_FIELDS, "Optimization round report next action", source);
		OptimizationRoundReport.Action action = OptimizationRoundReport.Action.fromExternalValue(
			requiredText(document, "action", "Optimization round report next action", source));
		String work = requiredText(document, "work", "Optimization round report next action", source);
		String reason = requiredText(document, "reason", "Optimization round report next action", source);
		return new OptimizationRoundReport.NextAction(action, work, reason);
	}

	/**
	 * Parses a persisted evidence kind.
	 *
	 * @param value the persisted value
	 * @return the closed evidence kind
	 */
	private static OptimizationRoundReport.EvidenceKind evidenceKind(String value)
	{
		return switch (value)
		{
			case "formal" -> OptimizationRoundReport.EvidenceKind.FORMAL;
			case "development" -> OptimizationRoundReport.EvidenceKind.DEVELOPMENT;
			default -> throw new IllegalArgumentException("Unsupported optimization report evidence kind: " + value);
		};
	}

	/**
	 * Parses a persisted family status.
	 *
	 * @param value the persisted value
	 * @return the closed family status
	 */
	private static OptimizationRoundReport.FamilyStatus familyStatus(String value)
	{
		return switch (value)
		{
			case "below_target" -> OptimizationRoundReport.FamilyStatus.BELOW_TARGET;
			case "futile" -> OptimizationRoundReport.FamilyStatus.FUTILE;
			case "invalid_evidence" -> OptimizationRoundReport.FamilyStatus.INVALID_EVIDENCE;
			default -> throw new IllegalArgumentException("Unsupported optimization report family status: " + value);
		};
	}

	/**
	 * Reads an object field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the report document path
	 * @return the object value
	 * @throws IOException if the field is not an object
	 */
	private static ObjectNode requiredObject(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (value instanceof ObjectNode object)
			return object;
		throw new IOException("Optimization round report has invalid " + field + ": " + source);
	}

	/**
	 * Reads an array field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the report document path
	 * @return the array value
	 * @throws IOException if the field is not an array
	 */
	private static ArrayNode requiredArray(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (value instanceof ArrayNode array)
			return array;
		throw new IOException("Optimization round report has invalid " + field + ": " + source);
	}

	/**
	 * Reads a required nonblank text field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param description the containing value's diagnostic description
	 * @param source the report document path
	 * @return the text
	 * @throws IOException if the value is not nonblank text
	 */
	private static String requiredText(ObjectNode document, String field, String description, Path source)
		throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException(description + " has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Reads a nullable probability field whose presence is required.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the report document path
	 * @return the probability, or empty for JSON null
	 * @throws IOException if the value is missing or not a probability
	 */
	private static OptionalDouble optionalProbability(ObjectNode document, String field, Path source)
		throws IOException
	{
		JsonNode value = document.get(field);
		if (value == null)
			throw new IOException("Optimization round report has missing " + field + ": " + source);
		if (value.isNull())
			return OptionalDouble.empty();
		return OptionalDouble.of(probability(value, field, source));
	}

	/**
	 * Reads a required probability field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the report document path
	 * @return the probability
	 * @throws IOException if the value is not a probability
	 */
	private static double probability(ObjectNode document, String field, Path source) throws IOException
	{
		return probability(document.path(field), field, source);
	}

	/**
	 * Reads a numeric probability node.
	 *
	 * @param value the value
	 * @param field the field name
	 * @param source the report document path
	 * @return the probability
	 * @throws IOException if the value is not between zero and one
	 */
	private static double probability(JsonNode value, String field, Path source) throws IOException
	{
		double result = value.doubleValue();
		if (!value.isNumber() || !Double.isFinite(result) || result < 0 || result > 1)
			throw new IOException("Optimization round report has invalid " + field + ": " + source);
		return result;
	}

	/**
	 * Reads a nonnegative long field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param description the containing value's diagnostic description
	 * @param source the report document path
	 * @return the value
	 * @throws IOException if the value is not a representable nonnegative long
	 */
	private static long nonnegativeLong(ObjectNode document, String field, String description, Path source)
		throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
			throw new IOException("Optimization round report " + description + " has invalid " + field + ": " + source);
		return value.asLong();
	}

	/**
	 * Reads a nonnegative integer field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param description the containing value's diagnostic description
	 * @param source the report document path
	 * @return the value
	 * @throws IOException if the value is not a representable nonnegative integer
	 */
	private static int nonnegativeInteger(ObjectNode document, String field, String description, Path source)
		throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0)
			throw new IOException("Optimization round report " + description + " has invalid " + field + ": " + source);
		return value.asInt();
	}

	/**
	 * Verifies one object's complete field set.
	 *
	 * @param object the source object
	 * @param expected the complete field set
	 * @param description the object's diagnostic description
	 * @param source the report document path
	 * @throws IOException if fields are missing or unsupported
	 */
	private static void verifyFields(ObjectNode object, Set<String> expected, String description, Path source)
		throws IOException
	{
		if (!new HashSet<>(object.propertyNames()).equals(expected))
			throw new IOException(description + " has unsupported or missing fields: " + source);
	}

	/**
	 * Prevents construction of this static document boundary.
	 */
	private OptimizationRoundReportDocument()
	{
	}
}
