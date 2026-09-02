package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.optimization.certification.FormalTrialLedger;
import io.github.cowwoc.cat.common.optimization.certification.FormalTrialOperation;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationOperation;
import io.github.cowwoc.cat.common.runner.HarnessRequestBinding;
import io.github.cowwoc.cat.common.RecordStore;
import io.github.cowwoc.cat.common.runner.RunnerArtifactStore;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.ProfileRole;
import io.github.cowwoc.cat.common.runner.SupportedModelProfiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executes one development candidate on one frozen promptTest and converts its evaluator verdict into a durable
 * paired-comparison outcome.
 */
public final class HarnessDevelopmentTrialExecutor
	implements DevelopmentComparisonOperation.CandidateTrialExecutor
{
	private static final int SCHEMA_VERSION = 1;
	private static final String RESULT_NAME = "development-trial.json";
	private static final Set<String> RESULT_FIELDS = Set.of("schema_version", "input_sha256", "passed", "session_id",
		"evidence_sha256", "input_tokens", "output_tokens", "elapsed_nanos");
	private final JsonMapper json;
	private final Path workRoot;
	private final Path baseArtifact;
	private final HarnessModelProfile profile;
	private final RequestFactory requestFactory;
	private final HarnessEvaluationRunner runner;
	private final EvaluatorFactory evaluatorFactory;

	/**
	 * Creates one harness-backed development trial adapter.
	 *
	 * @param json the operation-owned JSON codec
	 * @param workRoot the CAT-owned artifact-store root
	 * @param baseArtifact the verified packaged harness artifact
	 * @param profile the exact executor model profile
	 * @param requestFactory the harness-specific request factory
	 * @param runner the harness-neutral execution boundary
	 * @param evaluatorFactory the trial-scoped evaluator factory
	 */
	public HarnessDevelopmentTrialExecutor(JsonMapper json, Path workRoot, Path baseArtifact,
		HarnessModelProfile profile, RequestFactory requestFactory, HarnessEvaluationRunner runner,
		EvaluatorFactory evaluatorFactory)
	{
		this.json = Objects.requireNonNull(json, "json must not be null");
		this.workRoot = Objects.requireNonNull(workRoot, "workRoot must not be null").toAbsolutePath().normalize();
		this.baseArtifact = Objects.requireNonNull(baseArtifact, "baseArtifact must not be null").
			toAbsolutePath().normalize();
		this.profile = SupportedModelProfiles.requireSupported(ProfileRole.OPTIMIZER_EXECUTOR,
			Objects.requireNonNull(profile, "profile must not be null"));
		this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory must not be null");
		this.runner = Objects.requireNonNull(runner, "runner must not be null");
		this.evaluatorFactory = Objects.requireNonNull(evaluatorFactory, "evaluatorFactory must not be null");
	}

	/**
	 * Executes one candidate trial or returns its verified retained outcome.
	 *
	 * @param request the stable paired-comparison trial request
	 * @return the evaluator-defined pass/fail outcome and retained usage evidence
	 * @throws IOException if candidate delivery, harness execution, evaluation, or retention fails
	 */
	@Override
	public DevelopmentComparisonOperation.TrialOutcome execute(DevelopmentComparisonOperation.TrialRequest request)
		throws IOException
	{
		Objects.requireNonNull(request, "request must not be null");
		Path receiptRoot = request.receiptDirectory().toAbsolutePath().normalize();
		Path resultPath = receiptRoot.resolve(RESULT_NAME);
		DevelopmentComparisonOperation.TrialOutcome[] result = new DevelopmentComparisonOperation.TrialOutcome[1];
		RecordStore.withLock(resultPath, () ->
		{
			CandidateBundle.Candidate candidate = CandidateBundle.read(json, request.candidate().bundleRoot());
			if (!candidate.sha256().equals(request.candidate().sha256()))
				throw new IOException("Development trial candidate bundle changed");
			Path artifact = RunnerArtifactStore.retain(workRoot, baseArtifact, candidate);
			String inputFingerprint = inputFingerprint(request, artifact);
			if (Files.exists(resultPath))
			{
				result[0] = readResult(resultPath, inputFingerprint);
				return;
			}
			Files.createDirectories(receiptRoot);
			HarnessEvaluationRequest harnessRequest = requestFactory.create(profile, artifact, request.promptTest(),
				request.position(), receiptRoot);
			if (!harnessRequest.profile().equals(profile))
				throw new IOException("Development trial request changed the frozen executor profile");
			SupportedModelProfiles.requireSupported(ProfileRole.OPTIMIZER_EXECUTOR, harnessRequest.profile());
			if (!harnessRequest.artifact().equals(artifact))
				throw new IOException("Development trial request changed the retained candidate artifact");
			try
			{
				HarnessRequestBinding.requireExactPrompt(json, harnessRequest, request.promptTest().renderedPrompt(),
					"Development trial");
			}
			catch (IOException e)
			{
				throw new IOException("Development trial request changed the frozen prompt-test input", e);
			}
			HarnessEvaluationExecution execution = HarnessEvaluationOperation.execute(json, harnessRequest, runner);
			FormalTrialOperation.Evaluator evaluator = evaluatorFactory.create(request);
			FormalTrialOperation.Evaluation evaluation = evaluator.evaluate(execution);
			boolean passed = evaluation.verdict() == FormalTrialLedger.Verdict.PASS;
			long elapsed;
			try
			{
				elapsed = Math.subtractExact(execution.completedAtNanos(), execution.startedAtNanos());
			}
			catch (ArithmeticException e)
			{
				throw new IOException("Development trial elapsed time exceeds the supported range", e);
			}
			if (elapsed < 0)
				throw new IOException("Development trial completed before it started");
			String evidenceFingerprint = evidenceFingerprint(execution, evaluation);
			result[0] = new DevelopmentComparisonOperation.TrialOutcome(passed,
				harnessRequest.transcript().toString(), evidenceFingerprint, 0, 0, elapsed);
			RecordStore.write(json, resultPath, encodeResult(inputFingerprint, result[0]));
		});
		return result[0];
	}

	/**
	 * Creates the complete trial identity after candidate artifact publication.
	 *
	 * @param request the paired trial request
	 * @param artifact the content-addressed candidate artifact
	 * @return the input fingerprint
	 * @throws IOException if SHA-256 is unavailable
	 */
	private String inputFingerprint(DevelopmentComparisonOperation.TrialRequest request, Path artifact)
		throws IOException
	{
		ObjectNode identity = json.createObjectNode();
		identity.put("idempotency_key", request.idempotencyKey());
		identity.put("candidate_id", request.candidate().identifier());
		identity.put("candidate_sha256", request.candidate().sha256());
		identity.put("artifact", artifact.toString());
		identity.put("family_id", request.promptTest().familyIdentifier());
		identity.put("prompt_test_id", request.promptTest().instanceIdentifier());
		identity.put("prompt_test_sha256", request.promptTest().contentFingerprint());
		identity.put("position", request.position());
		identity.put("model", profile.model());
		identity.put("effort", profile.externalEffort());
		return Fingerprints.ofUtf8(json.writeValueAsString(identity));
	}

	/**
	 * Fingerprints executor and evaluator evidence together.
	 *
	 * @param execution the retained executor execution
	 * @param evaluation the retained evaluator decision
	 * @return the combined evidence fingerprint
	 * @throws IOException if retained evidence cannot be fingerprinted
	 */
	private static String evidenceFingerprint(HarnessEvaluationExecution execution,
		FormalTrialOperation.Evaluation evaluation) throws IOException
	{
		String project = Fingerprints.ofArtifact(execution.result().retainedProject());
		String transcript = Fingerprints.ofFile(execution.result().rawResult());
		return Fingerprints.ofUtf8(project + "\n" + transcript + "\n" + evaluation.evidenceFingerprint());
	}

	/**
	 * Encodes one completed trial result.
	 *
	 * @param inputFingerprint the input identity
	 * @param outcome the terminal outcome
	 * @return the result document
	 */
	private ObjectNode encodeResult(String inputFingerprint, DevelopmentComparisonOperation.TrialOutcome outcome)
	{
		ObjectNode result = json.createObjectNode();
		result.put("schema_version", SCHEMA_VERSION);
		result.put("input_sha256", inputFingerprint);
		result.put("passed", outcome.passed());
		result.put("session_id", outcome.sessionIdentifier());
		result.put("evidence_sha256", outcome.evidenceFingerprint());
		result.put("input_tokens", outcome.inputTokens());
		result.put("output_tokens", outcome.outputTokens());
		result.put("elapsed_nanos", outcome.elapsedNanos());
		return result;
	}

	/**
	 * Reads and verifies one retained trial result.
	 *
	 * @param resultPath the result path
	 * @param inputFingerprint the expected trial identity
	 * @return the retained outcome
	 * @throws IOException if the result is malformed or belongs to different input
	 */
	private DevelopmentComparisonOperation.TrialOutcome readResult(Path resultPath, String inputFingerprint)
		throws IOException
	{
		JsonNode parsed = RecordStore.read(json, resultPath);
		if (!(parsed instanceof ObjectNode result) || !new HashSet<>(result.propertyNames()).equals(RESULT_FIELDS))
			throw new IOException("Development trial result has unsupported or missing fields: " + resultPath);
		if (result.path("schema_version").asInt(-1) != SCHEMA_VERSION ||
			!requiredText(result, "input_sha256", resultPath).equals(inputFingerprint) ||
			!result.path("passed").isBoolean())
		{
			throw new IOException("Development trial result differs from its retained input: " + resultPath);
		}
		return new DevelopmentComparisonOperation.TrialOutcome(result.path("passed").asBoolean(),
			requiredText(result, "session_id", resultPath), requiredSha256(result, "evidence_sha256", resultPath),
			requiredNonnegativeLong(result, "input_tokens", resultPath),
			requiredNonnegativeLong(result, "output_tokens", resultPath),
			requiredNonnegativeLong(result, "elapsed_nanos", resultPath));
	}

	/**
	 * Requires one nonblank text field.
	 *
	 * @param object the object
	 * @param field the field name
	 * @param source the result path
	 * @return the value
	 * @throws IOException if the field is absent or blank
	 */
	private static String requiredText(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Development trial has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Requires one SHA-256 field.
	 *
	 * @param object the object
	 * @param field the field name
	 * @param source the result path
	 * @return the fingerprint
	 * @throws IOException if the value is invalid
	 */
	private static String requiredSha256(ObjectNode object, String field, Path source) throws IOException
	{
		String value = requiredText(object, field, source);
		if (!value.matches("[0-9a-f]{64}"))
			throw new IOException("Development trial has invalid " + field + ": " + source);
		return value;
	}

	/**
	 * Requires one nonnegative long field.
	 *
	 * @param object the object
	 * @param field the field name
	 * @param source the result path
	 * @return the value
	 * @throws IOException if the field is invalid
	 */
	private static long requiredNonnegativeLong(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
			throw new IOException("Development trial has invalid " + field + ": " + source);
		return value.asLong();
	}

	/**
	 * Creates the typed executor request after candidate artifact publication.
	 */
	@FunctionalInterface
	public interface RequestFactory
	{
		/**
		 * Creates one harness request.
		 *
		 * @param profile the exact executor profile
		 * @param retainedArtifact the content-addressed artifact containing the candidate
		 * @param promptTest the frozen development promptTest
		 * @param position the randomized paired position
		 * @param receiptRoot the durable trial root
		 * @return the typed harness request
		 * @throws IOException if request preparation fails
		 */
		HarnessEvaluationRequest create(HarnessModelProfile profile, Path retainedArtifact,
			PromptTestGenerationPolicy.PromptTest promptTest, int position, Path receiptRoot) throws IOException;
	}

	/**
	 * Creates the evaluator whose durable evidence belongs to one trial.
	 */
	@FunctionalInterface
	public interface EvaluatorFactory
	{
		/**
		 * Creates one trial-scoped evaluator.
		 *
		 * @param request the paired development trial request
		 * @return the evaluator
		 * @throws IOException if evaluator preparation fails
		 */
		FormalTrialOperation.Evaluator create(DevelopmentComparisonOperation.TrialRequest request) throws IOException;
	}
}
