package io.github.cowwoc.cat.common.optimization.evaluator;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.RecordStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executes a blinded evaluator corpus twice per case and retains cost-adjusted protocol approval evidence.
 */
public final class EvaluatorCalibrationOperation
{
	private static final int RECEIPT_SCHEMA_VERSION = 1;
	private static final int REPETITIONS = 2;
	private static final Set<String> RECEIPT_FIELDS = Set.of("schema_version", "input_sha256", "verdict",
		"observed_tokens", "elapsed_nanos");

	/**
	 * Freezes a corpus after verifying immutable evidence and complete mandatory-control coverage.
	 *
	 * @param cases the authored calibration cases
	 * @return the immutable, content-bound corpus
	 * @throws IOException if evidence is absent or cannot be fingerprinted
	 */
	public static Corpus freezeCorpus(List<CorpusCase> cases) throws IOException
	{
		Objects.requireNonNull(cases, "cases must not be null");
		if (cases.isEmpty())
			throw new IllegalArgumentException("cases must not be empty");
		List<CorpusCase> canonical = new ArrayList<>(cases);
		canonical.sort(Comparator.comparing(CorpusCase::identifier));
		List<FrozenCorpusCase> frozen = new ArrayList<>(canonical.size());
		Set<String> identifiers = new HashSet<>();
		Set<EvaluatorPolicy.Control> controls = EnumSet.noneOf(EvaluatorPolicy.Control.class);
		StringBuilder fingerprintInput = new StringBuilder();
		for (CorpusCase corpusCase : canonical)
		{
			Objects.requireNonNull(corpusCase, "cases must not contain null elements");
			if (!identifiers.add(corpusCase.identifier()))
				throw new IllegalArgumentException("Duplicate evaluator corpus case: " + corpusCase.identifier());
			Path evidence = corpusCase.evidencePath().toAbsolutePath().normalize();
			if (!Files.isRegularFile(evidence))
				throw new IOException("Expected evaluator corpus evidence file: " + evidence);
			String evidenceFingerprint = Fingerprints.ofFile(evidence);
			FrozenCorpusCase frozenCase = new FrozenCorpusCase(corpusCase.identifier(), evidence, evidenceFingerprint,
				corpusCase.expectedVerdict(), corpusCase.control());
			frozen.add(frozenCase);
			controls.add(corpusCase.control());
			fingerprintInput.append(corpusCase.identifier()).append('\0').append(evidenceFingerprint).append('\0').
				append(corpusCase.expectedVerdict().name()).append('\0').append(corpusCase.control().name()).append('\n');
		}
		Set<EvaluatorPolicy.Control> required = EnumSet.allOf(EvaluatorPolicy.Control.class);
		if (!controls.equals(required))
		{
			required.removeAll(controls);
			throw new IllegalArgumentException("Evaluator corpus is missing mandatory controls: " + required);
		}
		return new Corpus(frozen, Fingerprints.ofUtf8(fingerprintInput.toString()));
	}

	/**
	 * Executes or resumes one complete single-versus-adjudicated calibration operation.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param operationRoot the durable operation root
	 * @param policy the frozen calibration and selection policy
	 * @param singleProtocol the single-evaluator protocol
	 * @param adjudicatedProtocol the two-plus-tiebreaker protocol
	 * @param corpus the frozen untouched validation corpus
	 * @param runner the protocol execution boundary
	 * @return the persisted protocol selection and approval
	 * @throws IOException if evidence changes or a calibration decision cannot be retained
	 */
	public static EvaluatorCalibrationDocument.Loaded execute(JsonMapper json, Path operationRoot,
		EvaluatorPolicy policy, EvaluatorProtocol singleProtocol, EvaluatorProtocol adjudicatedProtocol, Corpus corpus,
		ProtocolRunner runner) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(operationRoot, "operationRoot must not be null");
		Objects.requireNonNull(policy, "policy must not be null");
		Objects.requireNonNull(singleProtocol, "singleProtocol must not be null");
		Objects.requireNonNull(adjudicatedProtocol, "adjudicatedProtocol must not be null");
		Objects.requireNonNull(corpus, "corpus must not be null");
		Objects.requireNonNull(runner, "runner must not be null");
		if (singleProtocol.aggregation() != EvaluatorProtocol.Aggregation.SINGLE ||
			adjudicatedProtocol.aggregation() != EvaluatorProtocol.Aggregation.TWO_PLUS_TIEBREAKER)
		{
			throw new IllegalArgumentException("Calibration protocols use the wrong aggregation forms");
		}
		Path normalizedRoot = operationRoot.toAbsolutePath().normalize();
		Path calibrationDocument = normalizedRoot.resolve("calibration.json");
		if (Files.exists(calibrationDocument))
		{
			EvaluatorCalibrationDocument.Loaded retained = EvaluatorCalibrationDocument.read(json,
				calibrationDocument);
			verifyRetained(retained, policy, singleProtocol, adjudicatedProtocol, corpus);
			return retained;
		}

		verifyEvidence(corpus);
		EvaluatorPolicy.CorpusReport singleReport = executeProtocol(json, normalizedRoot, singleProtocol, corpus,
			runner);
		EvaluatorPolicy.CorpusReport adjudicatedReport = executeProtocol(json, normalizedRoot, adjudicatedProtocol,
			corpus, runner);
		return EvaluatorCalibrationDocument.write(json, calibrationDocument, policy, singleProtocol,
			corpus.fingerprint(), singleReport, adjudicatedProtocol, corpus.fingerprint(), adjudicatedReport);
	}

	/**
	 * Verifies an existing terminal result belongs to the exact requested operation inputs.
	 *
	 * @param retained the retained terminal result
	 * @param policy the requested policy
	 * @param singleProtocol the requested single protocol
	 * @param adjudicatedProtocol the requested adjudicated protocol
	 * @param corpus the requested corpus
	 * @throws IOException if any determining input changed
	 */
	private static void verifyRetained(EvaluatorCalibrationDocument.Loaded retained, EvaluatorPolicy policy,
		EvaluatorProtocol singleProtocol, EvaluatorProtocol adjudicatedProtocol, Corpus corpus) throws IOException
	{
		if (!retained.policy().fingerprint().equals(policy.fingerprint()) ||
			!retained.singleProtocol().fingerprint().equals(singleProtocol.fingerprint()) ||
			!retained.adjudicatedProtocol().fingerprint().equals(adjudicatedProtocol.fingerprint()) ||
			!retained.singleCorpusFingerprint().equals(corpus.fingerprint()) ||
			!retained.adjudicatedCorpusFingerprint().equals(corpus.fingerprint()))
		{
			throw new IOException("Evaluator calibration retry changes retained operation input");
		}
	}

	/**
	 * Verifies every frozen evidence artifact still has its original content.
	 *
	 * @param corpus the frozen corpus
	 * @throws IOException if evidence is missing or changed
	 */
	private static void verifyEvidence(Corpus corpus) throws IOException
	{
		for (FrozenCorpusCase corpusCase : corpus.cases())
		{
			if (!Files.isRegularFile(corpusCase.evidencePath()) ||
				!Fingerprints.ofFile(corpusCase.evidencePath()).equals(corpusCase.evidenceFingerprint()))
			{
				throw new IOException("Evaluator corpus evidence changed: " + corpusCase.evidencePath());
			}
		}
	}

	/**
	 * Executes every corpus case twice and reduces the retained receipts to one report.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param operationRoot the normalized operation root
	 * @param protocol the calibrated protocol
	 * @param corpus the frozen corpus
	 * @param runner the protocol execution boundary
	 * @return the complete corpus report
	 * @throws IOException if a decision cannot execute or be retained
	 */
	private static EvaluatorPolicy.CorpusReport executeProtocol(JsonMapper json, Path operationRoot,
		EvaluatorProtocol protocol, Corpus corpus, ProtocolRunner runner) throws IOException
	{
		List<List<Decision>> decisions = new ArrayList<>(corpus.cases().size());
		for (int caseIndex = 0; caseIndex < corpus.cases().size(); ++caseIndex)
		{
			FrozenCorpusCase corpusCase = corpus.cases().get(caseIndex);
			List<Decision> repetitions = new ArrayList<>(REPETITIONS);
			for (int repetition = 1; repetition <= REPETITIONS; ++repetition)
			{
				String idempotencyKey = "evaluator-calibration/" + protocol.fingerprint() + "/" +
					corpus.fingerprint() + "/" + corpusCase.identifier() + "/" + repetition;
				Path receipt = operationRoot.resolve("evaluations").resolve(protocol.fingerprint()).
					resolve(String.format("%08d", caseIndex + 1)).resolve(repetition + ".json");
				EvaluationEvidence evidence = new EvaluationEvidence(corpusCase.identifier(), corpusCase.evidencePath(),
					corpusCase.evidenceFingerprint());
				repetitions.add(executeDecision(json, receipt, new EvaluationRequest(idempotencyKey, protocol,
					evidence, repetition), runner));
			}
			decisions.add(List.copyOf(repetitions));
		}
		return report(corpus, decisions);
	}

	/**
	 * Executes or reads one idempotent calibration decision.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param receiptPath the durable decision receipt
	 * @param request the exact evaluation request
	 * @param runner the protocol execution boundary
	 * @return the retained decision
	 * @throws IOException if execution or retained evidence is invalid
	 */
	private static Decision executeDecision(JsonMapper json, Path receiptPath, EvaluationRequest request,
		ProtocolRunner runner) throws IOException
	{
		String inputFingerprint = request.fingerprint();
		Decision[] result = new Decision[1];
		RecordStore.withLock(receiptPath, () ->
		{
			if (Files.exists(receiptPath))
			{
				result[0] = readDecision(json, receiptPath, inputFingerprint);
				return;
			}
			Decision decision = Objects.requireNonNull(runner.evaluate(request),
				"runner result must not be null");
			ObjectNode receipt = json.createObjectNode();
			receipt.put("schema_version", RECEIPT_SCHEMA_VERSION);
			receipt.put("input_sha256", inputFingerprint);
			receipt.put("verdict", decision.verdict().name().toLowerCase(java.util.Locale.ROOT));
			receipt.put("observed_tokens", decision.observedTokens());
			receipt.put("elapsed_nanos", decision.elapsed().toNanos());
			RecordStore.write(json, receiptPath, receipt);
			result[0] = decision;
		});
		return result[0];
	}

	/**
	 * Reads one strict retained decision.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param receiptPath the decision receipt
	 * @param inputFingerprint the expected request identity
	 * @return the retained decision
	 * @throws IOException if the receipt is malformed or belongs to another request
	 */
	private static Decision readDecision(JsonMapper json, Path receiptPath, String inputFingerprint) throws IOException
	{
		JsonNode parsed = RecordStore.read(json, receiptPath);
		if (!(parsed instanceof ObjectNode receipt) ||
			!new HashSet<>(receipt.propertyNames()).equals(RECEIPT_FIELDS) ||
			!receipt.path("schema_version").isIntegralNumber() ||
			receipt.path("schema_version").asInt(-1) != RECEIPT_SCHEMA_VERSION ||
			!requiredText(receipt, "input_sha256", receiptPath).equals(inputFingerprint))
		{
			throw new IOException("Evaluator calibration decision receipt is incompatible: " + receiptPath);
		}
		EvaluatorProtocol.Verdict verdict = switch (requiredText(receipt, "verdict", receiptPath))
		{
			case "pass" -> EvaluatorProtocol.Verdict.PASS;
			case "fail" -> EvaluatorProtocol.Verdict.FAIL;
			default -> throw new IOException("Evaluator calibration decision has an unsupported verdict: " + receiptPath);
		};
		JsonNode tokens = receipt.path("observed_tokens");
		JsonNode elapsed = receipt.path("elapsed_nanos");
		if (!tokens.isIntegralNumber() || !tokens.canConvertToLong() || !elapsed.isIntegralNumber() ||
			!elapsed.canConvertToLong())
		{
			throw new IOException("Evaluator calibration decision has invalid usage: " + receiptPath);
		}
		try
		{
			return new Decision(verdict, tokens.asLong(), Duration.ofNanos(elapsed.asLong()));
		}
		catch (IllegalArgumentException | DateTimeException e)
		{
			throw new IOException("Evaluator calibration decision has invalid values: " + receiptPath, e);
		}
	}

	/**
	 * Reduces repeated decisions to the exact policy report.
	 *
	 * @param corpus the frozen corpus
	 * @param decisions two retained decisions per case
	 * @return the complete report
	 */
	private static EvaluatorPolicy.CorpusReport report(Corpus corpus, List<List<Decision>> decisions)
	{
		ReportAccumulator accumulator = new ReportAccumulator();
		for (int index = 0; index < corpus.cases().size(); ++index)
		{
			FrozenCorpusCase corpusCase = corpus.cases().get(index);
			List<Decision> repetitions = decisions.get(index);
			if (repetitions.get(0).verdict() == repetitions.get(1).verdict())
				accumulator.recordReproduciblePair();
			for (Decision decision : repetitions)
				accumulator.record(corpusCase, decision);
		}
		return accumulator.toReport(corpus.cases().size());
	}

	/**
	 * Accumulates exact evaluator-corpus counts without exposing partially constructed reports.
	 */
	private static final class ReportAccumulator
	{
		private final Map<EvaluatorPolicy.Control, Boolean> passedControls =
			new EnumMap<>(EvaluatorPolicy.Control.class);
		private int total;
		private int correct;
		private int expectedFailing;
		private int expectedPassing;
		private int falsePasses;
		private int falseFails;
		private int reproduciblePairs;
		private long observedTokens;
		private Duration elapsed = Duration.ZERO;

		/**
		 * Creates an empty accumulator with every mandatory control passing until counterevidence appears.
		 */
		private ReportAccumulator()
		{
			for (EvaluatorPolicy.Control control : EvaluatorPolicy.Control.values())
				passedControls.put(control, true);
		}

		/**
		 * Records one agreeing repeated-decision pair.
		 */
		private void recordReproduciblePair()
		{
			++reproduciblePairs;
		}

		/**
		 * Records one terminal calibration decision.
		 *
		 * @param corpusCase the reference case
		 * @param decision the observed decision
		 */
		private void record(FrozenCorpusCase corpusCase, Decision decision)
		{
			++total;
			boolean matches = decision.verdict() == corpusCase.expectedVerdict();
			if (matches)
				++correct;
			else
				passedControls.put(corpusCase.control(), false);
			if (corpusCase.expectedVerdict() == EvaluatorProtocol.Verdict.FAIL)
			{
				++expectedFailing;
				if (!matches)
					++falsePasses;
			}
			else
			{
				++expectedPassing;
				if (!matches)
					++falseFails;
			}
			observedTokens = Math.addExact(observedTokens, decision.observedTokens());
			elapsed = elapsed.plus(decision.elapsed());
		}

		/**
		 * Produces the validated immutable report.
		 *
		 * @param repeatedPairs the number of corpus cases evaluated twice
		 * @return the complete report
		 */
		private EvaluatorPolicy.CorpusReport toReport(int repeatedPairs)
		{
			Set<EvaluatorPolicy.Control> passed = EnumSet.noneOf(EvaluatorPolicy.Control.class);
			for (Map.Entry<EvaluatorPolicy.Control, Boolean> entry : passedControls.entrySet())
			{
				if (entry.getValue())
					passed.add(entry.getKey());
			}
			return new EvaluatorPolicy.CorpusReport(total, correct, expectedFailing, expectedPassing, falsePasses,
				falseFails, repeatedPairs, reproduciblePairs, passed, observedTokens, elapsed);
		}
	}

	/**
	 * Reads one nonblank receipt field.
	 *
	 * @param document the containing receipt
	 * @param field the field name
	 * @param source the receipt path
	 * @return the text value
	 * @throws IOException if the field is absent or blank
	 */
	private static String requiredText(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Evaluator calibration decision has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Authored input for one blinded calibration case.
	 *
	 * @param identifier the stable case identifier
	 * @param evidencePath the complete local case artifact
	 * @param expectedVerdict the reference verdict hidden from the evaluator
	 * @param control the mandatory control exercised by the case
	 */
	public record CorpusCase(String identifier, Path evidencePath, EvaluatorProtocol.Verdict expectedVerdict,
		EvaluatorPolicy.Control control)
	{
		/**
		 * Validates one authored case.
		 *
		 * @param identifier the stable case identifier
		 * @param evidencePath the complete local case artifact
		 * @param expectedVerdict the reference verdict hidden from the evaluator
		 * @param control the mandatory control exercised by the case
		 */
		public CorpusCase
		{
			identifier = requireText(identifier, "identifier");
			Objects.requireNonNull(evidencePath, "evidencePath must not be null");
			Objects.requireNonNull(expectedVerdict, "expectedVerdict must not be null");
			Objects.requireNonNull(control, "control must not be null");
		}
	}

	/**
	 * Frozen, content-bound calibration case supplied to the execution boundary.
	 *
	 * @param identifier the stable case identifier
	 * @param evidencePath the normalized local case artifact
	 * @param evidenceFingerprint the exact case-artifact fingerprint
	 * @param expectedVerdict the reference verdict hidden by the runner integration
	 * @param control the mandatory control exercised by the case
	 */
	public record FrozenCorpusCase(String identifier, Path evidencePath, String evidenceFingerprint,
		EvaluatorProtocol.Verdict expectedVerdict, EvaluatorPolicy.Control control)
	{
		/**
		 * Validates one frozen case.
		 *
		 * @param identifier the stable case identifier
		 * @param evidencePath the normalized local case artifact
		 * @param evidenceFingerprint the exact case-artifact fingerprint
		 * @param expectedVerdict the reference verdict hidden by the runner integration
		 * @param control the mandatory control exercised by the case
		 */
		public FrozenCorpusCase
		{
			identifier = requireText(identifier, "identifier");
			Objects.requireNonNull(evidencePath, "evidencePath must not be null");
			evidenceFingerprint = requireSha256(evidenceFingerprint, "evidenceFingerprint");
			Objects.requireNonNull(expectedVerdict, "expectedVerdict must not be null");
			Objects.requireNonNull(control, "control must not be null");
		}
	}

	/**
	 * Holds one immutable calibration corpus.
	 */
	public static final class Corpus
	{
		private final List<FrozenCorpusCase> cases;
		private final String fingerprint;

		/**
		 * Creates one frozen corpus.
		 *
		 * @param cases the content-bound cases
		 * @param fingerprint the canonical corpus fingerprint
		 */
		private Corpus(List<FrozenCorpusCase> cases, String fingerprint)
		{
			this.cases = List.copyOf(cases);
			this.fingerprint = requireSha256(fingerprint, "fingerprint");
		}

		/**
		 * Returns the canonical ordered cases.
		 *
		 * @return the frozen cases
		 */
		public List<FrozenCorpusCase> cases()
		{
			return cases;
		}

		/**
		 * Returns the corpus identity.
		 *
		 * @return the lowercase SHA-256 fingerprint
		 */
		public String fingerprint()
		{
			return fingerprint;
		}
	}

	/**
	 * Executes one blinded evaluator protocol request.
	 */
	@FunctionalInterface
	public interface ProtocolRunner
	{
		/**
		 * Evaluates one case without exposing its expected verdict to the evaluator request.
		 *
		 * @param request the idempotent protocol and case request
		 * @return the final protocol decision and observed usage
		 * @throws IOException if evaluation infrastructure fails
		 */
		Decision evaluate(EvaluationRequest request) throws IOException;
	}

	/**
	 * Holds one exact calibration execution request.
	 *
	 * @param idempotencyKey the stable request key
	 * @param protocol the frozen evaluator protocol
	 * @param evidence the content-bound case evidence without its reference verdict or control label
	 * @param repetition the one-based reproducibility repetition
	 */
	public record EvaluationRequest(String idempotencyKey, EvaluatorProtocol protocol,
		EvaluationEvidence evidence, int repetition)
	{
		/**
		 * Validates one execution request.
		 *
		 * @param idempotencyKey the stable request key
		 * @param protocol the frozen evaluator protocol
		 * @param evidence the content-bound case evidence without its reference verdict or control label
		 * @param repetition the one-based reproducibility repetition
		 */
		public EvaluationRequest
		{
			idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
			Objects.requireNonNull(protocol, "protocol must not be null");
			Objects.requireNonNull(evidence, "evidence must not be null");
			if (repetition < 1 || repetition > REPETITIONS)
				throw new IllegalArgumentException("repetition must be one or two");
		}

		/**
		 * Calculates the complete immutable request identity.
		 *
		 * @return the lowercase SHA-256 fingerprint
		 */
		public String fingerprint()
		{
			return sha256(String.join("\0", idempotencyKey, protocol.fingerprint(), evidence.identifier(),
				evidence.fingerprint(), Integer.toString(repetition)));
		}
	}

	/**
	 * Holds only the blinded evidence fields supplied to a protocol runner.
	 *
	 * @param identifier the stable case identifier
	 * @param path the normalized local case artifact
	 * @param fingerprint the exact case-artifact fingerprint
	 */
	public record EvaluationEvidence(String identifier, Path path, String fingerprint)
	{
		/**
		 * Validates one blinded evidence reference.
		 *
		 * @param identifier the stable case identifier
		 * @param path the normalized local case artifact
		 * @param fingerprint the exact case-artifact fingerprint
		 */
		public EvaluationEvidence
		{
			identifier = requireText(identifier, "identifier");
			Objects.requireNonNull(path, "path must not be null");
			fingerprint = requireSha256(fingerprint, "fingerprint");
		}
	}

	/**
	 * Holds one terminal protocol decision and observed usage.
	 *
	 * @param verdict the final protocol verdict
	 * @param observedTokens total evaluator tokens consumed
	 * @param elapsed total evaluator execution time
	 */
	public record Decision(EvaluatorProtocol.Verdict verdict, long observedTokens, Duration elapsed)
	{
		/**
		 * Validates one terminal decision.
		 *
		 * @param verdict the final protocol verdict
		 * @param observedTokens total evaluator tokens consumed
		 * @param elapsed total evaluator execution time
		 */
		public Decision
		{
			Objects.requireNonNull(verdict, "verdict must not be null");
			if (observedTokens < 1)
				throw new IllegalArgumentException("observedTokens must be positive");
			Objects.requireNonNull(elapsed, "elapsed must not be null");
			if (elapsed.isNegative())
				throw new IllegalArgumentException("elapsed must not be negative");
		}
	}

	/**
	 * Validates required open text.
	 *
	 * @param value the proposed text
	 * @param name the parameter name
	 * @return the unchanged text
	 */
	private static String requireText(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Validates one lowercase SHA-256 value.
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
	 * Calculates one stable text fingerprint without exposing checked exception mechanics.
	 *
	 * @param value the canonical text
	 * @return the lowercase SHA-256 fingerprint
	 */
	private static String sha256(String value)
	{
		try
		{
			return Fingerprints.ofUtf8(value);
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
	}

	/**
	 * Prevents construction of this static operation owner.
	 */
	private EvaluatorCalibrationOperation()
	{
	}
}
