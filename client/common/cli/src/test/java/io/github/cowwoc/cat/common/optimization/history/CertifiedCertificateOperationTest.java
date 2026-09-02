package io.github.cowwoc.cat.common.optimization.history;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.optimization.certification.CandidateAllowanceLedger;
import io.github.cowwoc.cat.common.optimization.certification.FamilySequentialPolicyDocument;
import io.github.cowwoc.cat.common.optimization.certification.FormalTrialLedger;
import io.github.cowwoc.cat.common.optimization.certification.Statistics;
import io.github.cowwoc.cat.common.optimization.development.PromptTestIdentityLedger;
import io.github.cowwoc.cat.common.optimization.development.PromptTestPoolPartition;
import io.github.cowwoc.cat.common.optimization.evaluator.EvaluatorCalibrationDocument;
import io.github.cowwoc.cat.common.optimization.evaluator.EvaluatorPolicy;
import io.github.cowwoc.cat.common.optimization.evaluator.EvaluatorProtocol;
import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.ReasoningEffort;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests proof-directed local certificate issuance from verified formal ledgers.
 */
public final class CertifiedCertificateOperationTest
{
	private static final String CANDIDATE_SHA =
		"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String CONTRACT_SHA =
		"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
	private static final String ENVIRONMENT_SHA =
		"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public CertifiedCertificateOperationTest()
	{
	}

	/**
	 * Closes the shared JSON scope.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies only a certified decision can produce an idempotent candidate-bound local certificate.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void issuesCertifiedEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("certified-certificate-"));
			Fixture fixture = fixture(root, true);
			Path certificatePath = root.resolve("history/candidate-a.json");

			LocalCertificate first = CertifiedCertificateOperation.issue(scope.json(), fixture.allowanceManifest(),
				fixture.trialManifest(), fixture.statisticalPolicy(), fixture.calibration(),
				fixture.promptTestIdentityLedger(), root.resolve("keys"), certificatePath);
			LocalCertificate retry = CertifiedCertificateOperation.issue(scope.json(), fixture.allowanceManifest(),
				fixture.trialManifest(), fixture.statisticalPolicy(), fixture.calibration(),
				fixture.promptTestIdentityLedger(), root.resolve("keys"), certificatePath);

			requireThat(first.payload().terminalResult(), "terminalResult").
				isEqualTo(LocalCertificate.TerminalResult.CERTIFIED);
			requireThat(first.payload().candidateIdentifier(), "candidateIdentifier").isEqualTo("candidate-a");
			requireThat(first.payload().candidateFingerprint(), "candidateFingerprint").isEqualTo(CANDIDATE_SHA);
			requireThat(first.payload().currentLowerBound().orElseThrow(), "currentLowerBound").isGreaterThan(0.95);
			requireThat(retry.signature(), "retainedSignature").isEqualTo(first.signature());
		}
	}

	/**
	 * Verifies incomplete formal evidence cannot be mislabeled as certified.
	 *
	 * @throws IOException for the expected certification rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Formal evidence is not certified.*")
	public void rejectsUncertifiedEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("uncertified-certificate-"));
			Fixture fixture = fixture(root, false);

			CertifiedCertificateOperation.issue(scope.json(), fixture.allowanceManifest(), fixture.trialManifest(),
				fixture.statisticalPolicy(), fixture.calibration(), fixture.promptTestIdentityLedger(), root.resolve("keys"),
				root.resolve("history/candidate-a.json"));
		}
	}

	/**
	 * Creates one complete formal evidence fixture.
	 *
	 * @param root the fixture root
	 * @param complete whether enough passing formal trials are retained to certify
	 * @return the fixture paths and loaded policies
	 * @throws IOException if fixture evidence cannot be retained
	 */
	private Fixture fixture(Path root, boolean complete) throws IOException
	{
		Path policyPath = Files.writeString(root.resolve("statistical-policy.json"),
			statisticalPolicyJson());
		FamilySequentialPolicyDocument.Loaded statisticalPolicy =
			FamilySequentialPolicyDocument.read(scope.json(), policyPath);
		EvaluatorPolicy evaluatorPolicy = new EvaluatorPolicy("evaluator-policy-v1", 0.9, 0.9, 0.1, 0.1,
			0.01, 3);
		EvaluatorCalibrationDocument.Loaded calibration = calibration(root, evaluatorPolicy);
		Path allowances = root.resolve("allowances.json");
		CandidateAllowanceLedger.initialize(scope.json(), allowances, "lineage-a", new BigDecimal("0.05"),
			CandidateAllowanceLedger.Schedule.HALVING_V1);
		CandidateAllowanceLedger.Assignment assignment = CandidateAllowanceLedger.assign(scope.json(), allowances,
			"candidate-a", Instant.parse("2026-09-01T12:00:00Z"));
		Path trials = root.resolve("trials.json");
		FormalTrialLedger.initialize(scope.json(), trials, new FormalTrialLedger.Identity("candidate-a", CANDIDATE_SHA,
			CONTRACT_SHA, ENVIRONMENT_SHA, evaluatorPolicy.fingerprint(), statisticalPolicy.fingerprint(),
			assignment.entryFingerprint()), List.of("family-a"));
		Path identities = root.resolve("promptTest-identities.jsonl");
		PromptTestIdentityLedger.initialize(scope.json(), identities, CONTRACT_SHA);
		int trialCount = 1;
		if (complete)
			trialCount = Statistics.minimumPassingTrials(1_000, assignment.allowance().doubleValue(), 0.95);
		for (int trial = 1; trial <= trialCount; ++trial)
		{
			String promptTestIdentifier = "promptTest-" + trial;
			PromptTestPoolPartition.Identity identity = new PromptTestPoolPartition.Identity(
				PromptTestPoolPartition.Pool.FORMAL, promptTestIdentifier, "formal-seed-" + trial,
				Fingerprints.ofUtf8("formal-content-" + trial), "operation-" + trial);
			PromptTestIdentityLedger.reserve(scope.json(), identities, identity);
			FormalTrialLedger.record(scope.json(), trials, new FormalTrialLedger.Outcome("operation-" + trial,
				"family-a", promptTestIdentifier, FormalTrialLedger.Verdict.PASS,
				Fingerprints.ofUtf8("executor-" + trial), Optional.of(Fingerprints.ofUtf8("evaluator-" + trial)),
				Instant.parse("2026-09-01T12:00:00Z").plusSeconds(trial)));
		}
		return new Fixture(allowances, trials, identities, statisticalPolicy, calibration);
	}

	/**
	 * Persists one approved single-versus-adjudicated evaluator calibration.
	 *
	 * @param root the fixture root
	 * @param policy the evaluator selection policy
	 * @return the loaded calibration
	 * @throws IOException if the document cannot be retained
	 */
	private EvaluatorCalibrationDocument.Loaded calibration(Path root, EvaluatorPolicy policy) throws IOException
	{
		HarnessModelProfile profile = new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.HIGH);
		String prompt = Fingerprints.ofUtf8("evaluator prompt");
		String rubric = Fingerprints.ofUtf8("evaluator rubric");
		EvaluatorProtocol single = EvaluatorProtocol.single("single-v1", profile, prompt, rubric, 64 * 1024);
		EvaluatorProtocol adjudicated = EvaluatorProtocol.adjudicated("adjudicated-v1", profile, prompt, rubric,
			64 * 1024);
		EvaluatorPolicy.CorpusReport singleReport = report(10_000);
		EvaluatorPolicy.CorpusReport adjudicatedReport = report(25_000);
		return EvaluatorCalibrationDocument.write(scope.json(), root.resolve("evaluator-calibration.json"), policy,
			single, Fingerprints.ofUtf8("single-corpus"), singleReport, adjudicated,
			Fingerprints.ofUtf8("adjudicated-corpus"), adjudicatedReport);
	}

	/**
	 * Creates one passing evaluator report.
	 *
	 * @param tokens observed token usage
	 * @return the report
	 */
	private static EvaluatorPolicy.CorpusReport report(long tokens)
	{
		return new EvaluatorPolicy.CorpusReport(100, 100, 20, 80, 0, 0, 100, 100,
			EnumSet.allOf(EvaluatorPolicy.Control.class), tokens, Duration.ofMinutes(1));
	}

	/**
	 * Returns the fixed statistical policy document.
	 *
	 * @return the policy JSON
	 */
	private static String statisticalPolicyJson()
	{
		return """
			{
			  "schema_version":"family-sequential-policy-v1",
			  "identifier":"family-sequential-v1",
			  "statistical_revision":"uniform-mixture-anytime-v1",
			  "target":"0.95",
			  "maximum_trials_per_family":1000,
			  "lineage_error_budget":"0.05",
			  "candidate_allowance_schedule":"halving-v1"
			}
			""";
	}

	/**
	 * Holds the complete issuance fixture.
	 *
	 * @param allowanceManifest the lineage allowance manifest
	 * @param trialManifest the candidate formal-trial manifest
	 * @param promptTestIdentityLedger the contract promptTest identity ledger
	 * @param statisticalPolicy the loaded statistical policy
	 * @param calibration the persisted evaluator calibration
	 */
	private record Fixture(Path allowanceManifest, Path trialManifest, Path promptTestIdentityLedger,
		FamilySequentialPolicyDocument.Loaded statisticalPolicy, EvaluatorCalibrationDocument.Loaded calibration)
	{
	}
}
