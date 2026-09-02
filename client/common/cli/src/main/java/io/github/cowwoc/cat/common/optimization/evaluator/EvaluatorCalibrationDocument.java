package io.github.cowwoc.cat.common.optimization.evaluator;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.RecordStore;
import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.ProfileRole;
import io.github.cowwoc.cat.common.runner.SupportedModelProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Loads strict, persisted evaluator calibration reports and reconstructs their cost-adjusted protocol selection.
 */
public final class EvaluatorCalibrationDocument
{
	private static final String SCHEMA_VERSION = "evaluator-calibration-v1";
	private static final int MAXIMUM_DOCUMENT_BYTES = 4 * 1024 * 1024;
	private static final Set<String> ROOT_FIELDS = Set.of("schema_version", "policy", "single", "adjudicated");
	private static final Set<String> POLICY_FIELDS = Set.of("identifier", "minimum_accuracy",
		"minimum_reproducibility", "maximum_false_pass_rate", "maximum_false_fail_rate",
		"minimum_adjudication_accuracy_improvement", "maximum_adjudication_token_multiplier");
	private static final Set<String> CALIBRATION_FIELDS = Set.of("protocol", "corpus_sha256", "report");
	private static final Set<String> PROTOCOL_FIELDS = Set.of("identifier", "model", "reasoning_effort",
		"prompt_sha256", "rubric_sha256", "maximum_evidence_bytes", "aggregation");
	private static final Set<String> REPORT_FIELDS = Set.of("total_decisions", "correct_decisions",
		"expected_failing_decisions", "expected_passing_decisions", "false_passes", "false_fails",
		"repeated_pairs", "reproducible_pairs", "passed_controls", "observed_tokens", "elapsed_nanos");

	/**
	 * Persists one complete pair of calibration reports or returns its identical retained document.
	 *
	 * @param json the document-owned JSON mapper
	 * @param destination the durable calibration document
	 * @param policy the frozen selection policy
	 * @param singleProtocol the calibrated single-evaluator protocol
	 * @param singleCorpusFingerprint the untouched single-protocol corpus fingerprint
	 * @param singleReport the single-protocol execution report
	 * @param adjudicatedProtocol the calibrated adjudicated protocol
	 * @param adjudicatedCorpusFingerprint the untouched adjudicated-protocol corpus fingerprint
	 * @param adjudicatedReport the adjudicated-protocol execution report
	 * @return the verified persisted selection
	 * @throws IOException if an existing document differs or persistence fails
	 */
	public static Loaded write(JsonMapper json, Path destination, EvaluatorPolicy policy,
		EvaluatorProtocol singleProtocol, String singleCorpusFingerprint, EvaluatorPolicy.CorpusReport singleReport,
		EvaluatorProtocol adjudicatedProtocol, String adjudicatedCorpusFingerprint,
		EvaluatorPolicy.CorpusReport adjudicatedReport) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(destination, "destination must not be null");
		Objects.requireNonNull(policy, "policy must not be null");
		Objects.requireNonNull(singleProtocol, "singleProtocol must not be null");
		Objects.requireNonNull(singleReport, "singleReport must not be null");
		Objects.requireNonNull(adjudicatedProtocol, "adjudicatedProtocol must not be null");
		Objects.requireNonNull(adjudicatedReport, "adjudicatedReport must not be null");
		if (singleProtocol.aggregation() != EvaluatorProtocol.Aggregation.SINGLE ||
			adjudicatedProtocol.aggregation() != EvaluatorProtocol.Aggregation.TWO_PLUS_TIEBREAKER)
		{
			throw new IllegalArgumentException("Calibration protocols use the wrong aggregation forms");
		}
		singleCorpusFingerprint = requireSha256(singleCorpusFingerprint, "singleCorpusFingerprint");
		adjudicatedCorpusFingerprint = requireSha256(adjudicatedCorpusFingerprint,
			"adjudicatedCorpusFingerprint");
		Path normalized = destination.toAbsolutePath().normalize();
		ObjectNode encoded = encode(json, policy, singleProtocol, singleCorpusFingerprint, singleReport,
			adjudicatedProtocol, adjudicatedCorpusFingerprint, adjudicatedReport);
		String expectedFingerprint = Fingerprints.ofUtf8(json.writeValueAsString(encoded));
		RecordStore.withLock(normalized, () ->
		{
			if (Files.exists(normalized))
			{
				ObjectNode retained = requiredDocument(json, normalized);
				String retainedFingerprint = Fingerprints.ofUtf8(json.writeValueAsString(retained));
				if (!retainedFingerprint.equals(expectedFingerprint))
					throw new IOException("Evaluator calibration retry changes retained evidence: " + normalized);
				return;
			}
			RecordStore.write(json, normalized, encoded);
		});
		return read(json, normalized);
	}

	/**
	 * Reads, validates, and deterministically selects a protocol from one calibration document.
	 *
	 * @param json the caller-owned JSON mapper
	 * @param source the persisted calibration document
	 * @return the exact policy, protocols, selection, and source fingerprint
	 * @throws IOException if the document is absent, oversized, malformed, or unsupported
	 */
	public static Loaded read(JsonMapper json, Path source) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(source, "source must not be null");
		Path normalized = source.toAbsolutePath().normalize();
		if (!Files.isRegularFile(normalized))
			throw new IOException("Expected evaluator calibration document: " + normalized);
		if (Files.size(normalized) > MAXIMUM_DOCUMENT_BYTES)
			throw new IOException("Evaluator calibration document exceeds its size limit: " + normalized);

		ObjectNode document = requiredDocument(json, normalized);
		requireFields(document, ROOT_FIELDS, "Evaluator calibration document", normalized);
		if (!requiredText(document, "schema_version", normalized).equals(SCHEMA_VERSION))
			throw new IOException("Unsupported evaluator calibration schema: " + normalized);

		EvaluatorPolicy policy = policy(requiredObject(document, "policy", normalized), normalized);
		Calibration single = calibration(requiredObject(document, "single", normalized), normalized,
			EvaluatorProtocol.Aggregation.SINGLE);
		Calibration adjudicated = calibration(requiredObject(document, "adjudicated", normalized), normalized,
			EvaluatorProtocol.Aggregation.TWO_PLUS_TIEBREAKER);
		EvaluatorPolicy.ProtocolSelection selection = policy.selectProtocols(single.protocol(),
			single.corpusFingerprint(), single.report(), adjudicated.protocol(), adjudicated.corpusFingerprint(),
			adjudicated.report());
		return new Loaded(policy, single.protocol(), single.corpusFingerprint(), single.report(),
			adjudicated.protocol(), adjudicated.corpusFingerprint(), adjudicated.report(), selection,
			Fingerprints.ofFile(normalized));
	}

	/**
	 * Reads one strict calibration object without applying its domain schema.
	 *
	 * @param json the caller-owned JSON mapper
	 * @param source the source path
	 * @return the parsed object
	 * @throws IOException if JSON syntax or the root type is invalid
	 */
	private static ObjectNode requiredDocument(JsonMapper json, Path source) throws IOException
	{
		try
		{
			JsonMapper strict = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
			JsonNode parsed = strict.readTree(source.toFile());
			if (parsed instanceof ObjectNode object)
				return object;
			throw new IOException("Evaluator calibration document is not a JSON object: " + source);
		}
		catch (JacksonException e)
		{
			throw new IOException("Invalid evaluator calibration JSON: " + source, e);
		}
	}

	/**
	 * Encodes one canonical calibration document.
	 *
	 * @param json the document-owned JSON mapper
	 * @param policy the frozen selection policy
	 * @param singleProtocol the single protocol
	 * @param singleCorpusFingerprint the single corpus fingerprint
	 * @param singleReport the single report
	 * @param adjudicatedProtocol the adjudicated protocol
	 * @param adjudicatedCorpusFingerprint the adjudicated corpus fingerprint
	 * @param adjudicatedReport the adjudicated report
	 * @return the canonical document
	 */
	private static ObjectNode encode(JsonMapper json, EvaluatorPolicy policy, EvaluatorProtocol singleProtocol,
		String singleCorpusFingerprint, EvaluatorPolicy.CorpusReport singleReport,
		EvaluatorProtocol adjudicatedProtocol, String adjudicatedCorpusFingerprint,
		EvaluatorPolicy.CorpusReport adjudicatedReport)
	{
		ObjectNode root = json.createObjectNode();
		root.put("schema_version", SCHEMA_VERSION);
		EvaluatorPolicy.Configuration configuration = policy.configuration();
		ObjectNode policyNode = root.putObject("policy");
		policyNode.put("identifier", configuration.identifier());
		policyNode.put("minimum_accuracy", configuration.minimumAccuracy());
		policyNode.put("minimum_reproducibility", configuration.minimumReproducibility());
		policyNode.put("maximum_false_pass_rate", configuration.maximumFalsePassRate());
		policyNode.put("maximum_false_fail_rate", configuration.maximumFalseFailRate());
		policyNode.put("minimum_adjudication_accuracy_improvement",
			configuration.minimumAdjudicationAccuracyImprovement());
		policyNode.put("maximum_adjudication_token_multiplier",
			configuration.maximumAdjudicationTokenMultiplier());
		root.set("single", encodeCalibration(json, singleProtocol, singleCorpusFingerprint, singleReport));
		root.set("adjudicated", encodeCalibration(json, adjudicatedProtocol, adjudicatedCorpusFingerprint,
			adjudicatedReport));
		return root;
	}

	/**
	 * Encodes one exact protocol and corpus report.
	 *
	 * @param json the document-owned JSON mapper
	 * @param protocol the calibrated protocol
	 * @param corpusFingerprint the untouched corpus fingerprint
	 * @param report the corpus report
	 * @return the calibration object
	 */
	private static ObjectNode encodeCalibration(JsonMapper json, EvaluatorProtocol protocol,
		String corpusFingerprint, EvaluatorPolicy.CorpusReport report)
	{
		ObjectNode calibration = json.createObjectNode();
		ObjectNode protocolNode = calibration.putObject("protocol");
		protocolNode.put("identifier", protocol.identifier());
		protocolNode.put("model", protocol.modelProfile().model());
		protocolNode.put("reasoning_effort", protocol.modelProfile().externalEffort());
		protocolNode.put("prompt_sha256", protocol.evaluatorPromptFingerprint());
		protocolNode.put("rubric_sha256", protocol.rubricFingerprint());
		protocolNode.put("maximum_evidence_bytes", protocol.maximumEvidenceBytes());
		String aggregation = "single";
		if (protocol.aggregation() == EvaluatorProtocol.Aggregation.TWO_PLUS_TIEBREAKER)
			aggregation = "two_plus_tiebreaker";
		protocolNode.put("aggregation", aggregation);
		calibration.put("corpus_sha256", corpusFingerprint);
		calibration.set("report", encodeReport(json, report));
		return calibration;
	}

	/**
	 * Encodes one corpus execution report.
	 *
	 * @param json the document-owned JSON mapper
	 * @param report the report
	 * @return the report object
	 */
	private static ObjectNode encodeReport(JsonMapper json, EvaluatorPolicy.CorpusReport report)
	{
		ObjectNode result = json.createObjectNode();
		result.put("total_decisions", report.totalDecisions());
		result.put("correct_decisions", report.correctDecisions());
		result.put("expected_failing_decisions", report.expectedFailingDecisions());
		result.put("expected_passing_decisions", report.expectedPassingDecisions());
		result.put("false_passes", report.falsePasses());
		result.put("false_fails", report.falseFails());
		result.put("repeated_pairs", report.repeatedPairs());
		result.put("reproducible_pairs", report.reproduciblePairs());
		ArrayNode controls = result.putArray("passed_controls");
		report.passedControls().stream().map(EvaluatorCalibrationDocument::externalControl).sorted().
			forEach(controls::add);
		result.put("observed_tokens", report.observedTokens());
		result.put("elapsed_nanos", report.elapsed().toNanos());
		return result;
	}

	/**
	 * Returns one control's stable external name.
	 *
	 * @param control the control
	 * @return the external name
	 */
	private static String externalControl(EvaluatorPolicy.Control control)
	{
		return control.name().toLowerCase(java.util.Locale.ROOT);
	}

	/**
	 * Reconstructs one policy from persisted thresholds.
	 *
	 * @param document the policy object
	 * @param source the source path
	 * @return the validated policy
	 * @throws IOException if a field is missing or invalid
	 */
	private static EvaluatorPolicy policy(ObjectNode document, Path source) throws IOException
	{
		requireFields(document, POLICY_FIELDS, "Evaluator policy", source);
		try
		{
			return new EvaluatorPolicy(requiredText(document, "identifier", source),
				requiredDouble(document, "minimum_accuracy", source),
				requiredDouble(document, "minimum_reproducibility", source),
				requiredDouble(document, "maximum_false_pass_rate", source),
				requiredDouble(document, "maximum_false_fail_rate", source),
				requiredDouble(document, "minimum_adjudication_accuracy_improvement", source),
				requiredDouble(document, "maximum_adjudication_token_multiplier", source));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Evaluator calibration policy is invalid: " + source, e);
		}
	}

	/**
	 * Reconstructs one exact protocol and its untouched-corpus report.
	 *
	 * @param document the calibration object
	 * @param source the source path
	 * @param expectedAggregation the required protocol form
	 * @return the calibrated protocol evidence
	 * @throws IOException if a field is missing, invalid, or names another aggregation
	 */
	private static Calibration calibration(ObjectNode document, Path source,
		EvaluatorProtocol.Aggregation expectedAggregation) throws IOException
	{
		requireFields(document, CALIBRATION_FIELDS, "Evaluator protocol calibration", source);
		ObjectNode protocolDocument = requiredObject(document, "protocol", source);
		requireFields(protocolDocument, PROTOCOL_FIELDS, "Evaluator protocol", source);
		String aggregation = requiredText(protocolDocument, "aggregation", source);
		String expectedExternal = switch (expectedAggregation)
		{
			case SINGLE -> "single";
			case TWO_PLUS_TIEBREAKER -> "two_plus_tiebreaker";
		};
		if (!aggregation.equals(expectedExternal))
			throw new IOException("Evaluator calibration has an unexpected aggregation: " + aggregation);
		try
		{
			HarnessModelProfile profile = HarnessModelProfile.fromExternalValues(
				requiredText(protocolDocument, "model", source),
				requiredText(protocolDocument, "reasoning_effort", source));
			profile = SupportedModelProfiles.requireSupported(ProfileRole.SEMANTIC_EVALUATOR, profile);
			String identifier = requiredText(protocolDocument, "identifier", source);
			String promptFingerprint = requiredSha256(protocolDocument, "prompt_sha256", source);
			String rubricFingerprint = requiredSha256(protocolDocument, "rubric_sha256", source);
			int maximumEvidenceBytes = requiredPositiveInt(protocolDocument, "maximum_evidence_bytes", source);
			EvaluatorProtocol protocol;
			if (expectedAggregation == EvaluatorProtocol.Aggregation.SINGLE)
			{
				protocol = EvaluatorProtocol.single(identifier, profile, promptFingerprint, rubricFingerprint,
					maximumEvidenceBytes);
			}
			else
			{
				protocol = EvaluatorProtocol.adjudicated(identifier, profile, promptFingerprint, rubricFingerprint,
					maximumEvidenceBytes);
			}
			return new Calibration(protocol, requiredSha256(document, "corpus_sha256", source),
				report(requiredObject(document, "report", source), source));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Evaluator protocol calibration is invalid: " + source, e);
		}
	}

	/**
	 * Reconstructs one untouched-corpus execution report.
	 *
	 * @param document the report object
	 * @param source the source path
	 * @return the validated report
	 * @throws IOException if a field is missing or invalid
	 */
	private static EvaluatorPolicy.CorpusReport report(ObjectNode document, Path source) throws IOException
	{
		requireFields(document, REPORT_FIELDS, "Evaluator corpus report", source);
		JsonNode controlsNode = document.path("passed_controls");
		if (!(controlsNode instanceof ArrayNode controlsArray))
			throw new IOException("Evaluator corpus report passed_controls must be an array: " + source);
		Set<EvaluatorPolicy.Control> controls = EnumSet.noneOf(EvaluatorPolicy.Control.class);
		for (JsonNode value : controlsArray)
		{
			if (!value.isString())
				throw new IOException("Evaluator corpus report contains a non-string control: " + source);
			EvaluatorPolicy.Control control = control(value.asString(), source);
			if (!controls.add(control))
				throw new IOException("Evaluator corpus report repeats a control: " + value.asString());
		}
		try
		{
			return new EvaluatorPolicy.CorpusReport(requiredInt(document, "total_decisions", source),
				requiredInt(document, "correct_decisions", source),
				requiredInt(document, "expected_failing_decisions", source),
				requiredInt(document, "expected_passing_decisions", source),
				requiredInt(document, "false_passes", source), requiredInt(document, "false_fails", source),
				requiredInt(document, "repeated_pairs", source), requiredInt(document, "reproducible_pairs", source),
				controls, requiredLong(document, "observed_tokens", source),
				Duration.ofNanos(requiredLong(document, "elapsed_nanos", source)));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Evaluator corpus report is invalid: " + source, e);
		}
	}

	/**
	 * Parses one supported adversarial control.
	 *
	 * @param value the external control name
	 * @param source the source path
	 * @return the constrained control
	 * @throws IOException if the value is unsupported
	 */
	private static EvaluatorPolicy.Control control(String value, Path source) throws IOException
	{
		return switch (value)
		{
			case "known_pass" -> EvaluatorPolicy.Control.KNOWN_PASS;
			case "known_fail" -> EvaluatorPolicy.Control.KNOWN_FAIL;
			case "boundary" -> EvaluatorPolicy.Control.BOUNDARY;
			case "ambiguous_evidence" -> EvaluatorPolicy.Control.AMBIGUOUS_EVIDENCE;
			case "prompt_injection" -> EvaluatorPolicy.Control.PROMPT_INJECTION;
			case "self_preference" -> EvaluatorPolicy.Control.SELF_PREFERENCE;
			case "position_bias" -> EvaluatorPolicy.Control.POSITION_BIAS;
			case "rubric_manipulation" -> EvaluatorPolicy.Control.RUBRIC_MANIPULATION;
			case "unsupported_evidence" -> EvaluatorPolicy.Control.UNSUPPORTED_EVIDENCE;
			default -> throw new IOException("Unsupported evaluator corpus control " + value + ": " + source);
		};
	}

	/**
	 * Requires one exact object field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the source path
	 * @return the child object
	 * @throws IOException if the field is not an object
	 */
	private static ObjectNode requiredObject(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (value instanceof ObjectNode object)
			return object;
		throw new IOException("Evaluator calibration has invalid " + field + ": " + source);
	}

	/**
	 * Requires an exact field set.
	 *
	 * @param document the checked object
	 * @param expected the supported field set
	 * @param description the object description
	 * @param source the source path
	 * @throws IOException if fields are missing or unsupported
	 */
	private static void requireFields(ObjectNode document, Set<String> expected, String description, Path source)
		throws IOException
	{
		if (!new HashSet<>(document.propertyNames()).equals(expected))
			throw new IOException(description + " has unsupported or missing fields: " + source);
	}

	/**
	 * Requires one nonblank string field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the source path
	 * @return the text value
	 * @throws IOException if the field is absent or blank
	 */
	private static String requiredText(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Evaluator calibration has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Requires one SHA-256 string field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the source path
	 * @return the fingerprint
	 * @throws IOException if the field is invalid
	 */
	private static String requiredSha256(ObjectNode document, String field, Path source) throws IOException
	{
		String value = requiredText(document, field, source);
		if (!value.matches("[0-9a-f]{64}"))
			throw new IOException("Evaluator calibration has invalid " + field + ": " + source);
		return value;
	}

	/**
	 * Validates one lowercase SHA-256 value supplied to the writer.
	 *
	 * @param value the proposed fingerprint
	 * @param name the parameter name
	 * @return the unchanged fingerprint
	 */
	private static String requireSha256(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (!value.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
		return value;
	}

	/**
	 * Requires one finite numeric field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the source path
	 * @return the double value
	 * @throws IOException if the field is not finite numeric input
	 */
	private static double requiredDouble(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		double result = value.asDouble(Double.NaN);
		if (!value.isNumber() || !Double.isFinite(result))
			throw new IOException("Evaluator calibration has invalid " + field + ": " + source);
		return result;
	}

	/**
	 * Requires one exact integer field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the source path
	 * @return the integer value
	 * @throws IOException if the field is not a 32-bit integer
	 */
	private static int requiredInt(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToInt())
			throw new IOException("Evaluator calibration has invalid " + field + ": " + source);
		return value.asInt();
	}

	/**
	 * Requires one positive exact integer field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the source path
	 * @return the positive integer
	 * @throws IOException if the field is not positive
	 */
	private static int requiredPositiveInt(ObjectNode document, String field, Path source) throws IOException
	{
		int result = requiredInt(document, field, source);
		if (result < 1)
			throw new IOException("Evaluator calibration has invalid " + field + ": " + source);
		return result;
	}

	/**
	 * Requires one exact long field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the source path
	 * @return the long value
	 * @throws IOException if the field is not a 64-bit integer
	 */
	private static long requiredLong(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToLong())
			throw new IOException("Evaluator calibration has invalid " + field + ": " + source);
		return value.asLong();
	}

	/**
	 * Holds one loaded, deterministic calibration result.
	 *
	 * @param policy the frozen evaluator policy
	 * @param singleProtocol the calibrated single-evaluator protocol
	 * @param singleCorpusFingerprint the single-protocol untouched-corpus fingerprint
	 * @param singleReport the single-protocol corpus report
	 * @param adjudicatedProtocol the calibrated two-plus-tiebreaker protocol
	 * @param adjudicatedCorpusFingerprint the adjudicated-protocol untouched-corpus fingerprint
	 * @param adjudicatedReport the adjudicated-protocol corpus report
	 * @param selection the recomputed cost-adjusted selection and approval
	 * @param fingerprint the exact source-document fingerprint
	 */
	public record Loaded(EvaluatorPolicy policy, EvaluatorProtocol singleProtocol, String singleCorpusFingerprint,
		EvaluatorPolicy.CorpusReport singleReport, EvaluatorProtocol adjudicatedProtocol,
		String adjudicatedCorpusFingerprint, EvaluatorPolicy.CorpusReport adjudicatedReport,
		EvaluatorPolicy.ProtocolSelection selection, String fingerprint)
	{
		/**
		 * Validates one loaded calibration result.
		 *
		 * @param policy the frozen evaluator policy
		 * @param singleProtocol the calibrated single-evaluator protocol
		 * @param singleCorpusFingerprint the single-protocol untouched-corpus fingerprint
		 * @param singleReport the single-protocol corpus report
		 * @param adjudicatedProtocol the calibrated two-plus-tiebreaker protocol
		 * @param adjudicatedCorpusFingerprint the adjudicated-protocol untouched-corpus fingerprint
		 * @param adjudicatedReport the adjudicated-protocol corpus report
		 * @param selection the recomputed cost-adjusted selection and approval
		 * @param fingerprint the exact source-document fingerprint
		 */
		public Loaded
		{
			Objects.requireNonNull(policy, "policy must not be null");
			Objects.requireNonNull(singleProtocol, "singleProtocol must not be null");
			singleCorpusFingerprint = requireSha256(singleCorpusFingerprint, "singleCorpusFingerprint");
			Objects.requireNonNull(singleReport, "singleReport must not be null");
			Objects.requireNonNull(adjudicatedProtocol, "adjudicatedProtocol must not be null");
			adjudicatedCorpusFingerprint = requireSha256(adjudicatedCorpusFingerprint,
				"adjudicatedCorpusFingerprint");
			Objects.requireNonNull(adjudicatedReport, "adjudicatedReport must not be null");
			Objects.requireNonNull(selection, "selection must not be null");
			if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("fingerprint must be a lowercase SHA-256 value");
		}

		/**
		 * Returns the exact selected protocol, or empty when neither calibrated protocol passed policy.
		 *
		 * @return the selected protocol
		 */
		public Optional<EvaluatorProtocol> selectedProtocol()
		{
			return switch (selection.selection().protocol())
			{
				case SINGLE -> Optional.of(singleProtocol);
				case ADJUDICATED -> Optional.of(adjudicatedProtocol);
				case NONE -> Optional.empty();
			};
		}
	}

	/**
	 * Holds one exact protocol and its calibration evidence.
	 *
	 * @param protocol the calibrated protocol
	 * @param corpusFingerprint the untouched corpus fingerprint
	 * @param report the corpus execution report
	 */
	private record Calibration(EvaluatorProtocol protocol, String corpusFingerprint,
		EvaluatorPolicy.CorpusReport report)
	{
	}

	/**
	 * Prevents construction of this static document loader.
	 */
	private EvaluatorCalibrationDocument()
	{
	}
}
