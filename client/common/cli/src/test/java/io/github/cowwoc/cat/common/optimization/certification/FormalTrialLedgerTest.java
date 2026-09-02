package io.github.cowwoc.cat.common.optimization.certification;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests append-only formal family outcomes and their bounded decision summary.
 */
public final class FormalTrialLedgerTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public FormalTrialLedgerTest()
	{
	}

	/**
	 * Closes the JSON scope after the tests complete.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies formal verdicts count by family, infrastructure failures do not, and identical retries are idempotent.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsFamilyEvidenceIdempotently() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("formal-trial-ledger-"));
			Path manifest = initialize(root);
			FormalTrialLedger.Outcome pass = outcome("operation-a", "family-a", "promptTest-a",
				FormalTrialLedger.Verdict.PASS, true);
			FormalTrialLedger.RetainedOutcome first = FormalTrialLedger.record(scope.json(), manifest, pass);
			FormalTrialLedger.RetainedOutcome retry = FormalTrialLedger.record(scope.json(), manifest, pass);
			FormalTrialLedger.record(scope.json(), manifest, outcome("operation-b", "family-b", "promptTest-b",
				FormalTrialLedger.Verdict.FAIL, true));
			FormalTrialLedger.record(scope.json(), manifest, outcome("operation-c", "family-a", "promptTest-c",
				FormalTrialLedger.Verdict.INFRASTRUCTURE_FAILURE, false));

			FormalTrialLedger.Summary summary = FormalTrialLedger.read(scope.json(), manifest);

			requireThat(retry, "retry").isEqualTo(first);
			requireThat(summary.entryCount(), "entryCount").isEqualTo(3);
			requireThat(summary.infrastructureFailureCount(), "infrastructureFailureCount").isEqualTo(1);
			requireThat(summary.evidence().get(0), "familyAEvidence").
				isEqualTo(new FamilyCertification.Evidence("family-a", 1, 1));
			requireThat(summary.evidence().get(1), "familyBEvidence").
				isEqualTo(new FamilyCertification.Evidence("family-b", 0, 1));
		}
	}

	/**
	 * Verifies a later retry returns the original retained completion time instead of treating the retry clock as a
	 * changed operation input.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void ignoresRetryClockForIdenticalEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("formal-trial-ledger-"));
			Path manifest = initialize(root);
			FormalTrialLedger.Outcome firstOutcome = outcome("operation-a", "family-a", "promptTest-a",
				FormalTrialLedger.Verdict.PASS, true);
			FormalTrialLedger.RetainedOutcome first = FormalTrialLedger.record(scope.json(), manifest, firstOutcome);
			FormalTrialLedger.Outcome laterRetry = new FormalTrialLedger.Outcome(firstOutcome.operationId(),
				firstOutcome.familyId(), firstOutcome.promptTestId(), firstOutcome.verdict(),
				firstOutcome.executorEvidenceFingerprint(), firstOutcome.evaluatorEvidenceFingerprint(),
				Instant.parse("2026-09-01T13:00:00Z"));

			FormalTrialLedger.RetainedOutcome retry = FormalTrialLedger.record(scope.json(), manifest, laterRetry);

			requireThat(retry, "retry").isEqualTo(first);
			requireThat(retry.outcome().completedAt(), "retainedCompletionTime").isEqualTo(firstOutcome.completedAt());
		}
	}

	/**
	 * Verifies an idempotency key cannot name different formal evidence.
	 *
	 * @throws IOException for the expected identity conflict
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Operation identity already records different formal trial input.*")
	public void rejectsChangedInputForAnOperation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("formal-trial-ledger-"));
			Path manifest = initialize(root);
			FormalTrialLedger.record(scope.json(), manifest, outcome("operation-a", "family-a", "promptTest-a",
				FormalTrialLedger.Verdict.PASS, true));

			FormalTrialLedger.record(scope.json(), manifest, outcome("operation-a", "family-a", "promptTest-a",
				FormalTrialLedger.Verdict.FAIL, true));
		}
	}

	/**
	 * Verifies one formal promptTest instance cannot be counted or retried under another operation.
	 *
	 * @throws IOException for the expected freshness failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Formal promptTest was already attempted: promptTest-a.*")
	public void rejectsAReusedFormalPromptTest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("formal-trial-ledger-"));
			Path manifest = initialize(root);
			FormalTrialLedger.record(scope.json(), manifest, outcome("operation-a", "family-a", "promptTest-a",
				FormalTrialLedger.Verdict.INFRASTRUCTURE_FAILURE, false));

			FormalTrialLedger.record(scope.json(), manifest, outcome("operation-b", "family-a", "promptTest-a",
				FormalTrialLedger.Verdict.PASS, true));
		}
	}

	/**
	 * Verifies an outcome cannot invent a family after the contract was frozen.
	 *
	 * @throws IOException for the expected family rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Formal trial names an undeclared family: invented.*")
	public void rejectsAnUndeclaredFamily() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("formal-trial-ledger-"));
			Path manifest = initialize(root);

			FormalTrialLedger.record(scope.json(), manifest, outcome("operation-a", "invented", "promptTest-a",
				FormalTrialLedger.Verdict.PASS, true));
		}
	}

	/**
	 * Creates one frozen two-family formal ledger.
	 *
	 * @param root the fixture root
	 * @return the manifest path
	 * @throws IOException if the fixture cannot be initialized
	 */
	private Path initialize(Path root) throws IOException
	{
		Path manifest = root.resolve("formal-trials.json");
		FormalTrialLedger.Identity identity = new FormalTrialLedger.Identity("candidate-a", fingerprint('a'),
			fingerprint('b'), fingerprint('c'), fingerprint('d'), fingerprint('e'), fingerprint('f'));
		FormalTrialLedger.initialize(scope.json(), manifest, identity, List.of("family-a", "family-b"));
		return manifest;
	}

	/**
	 * Creates one formal outcome fixture.
	 *
	 * @param operationId the idempotent operation identifier
	 * @param familyId the declared family identifier
	 * @param promptTestId the fresh formal promptTest identifier
	 * @param verdict the typed evaluator or infrastructure result
	 * @param evaluated whether evaluator evidence exists
	 * @return the immutable outcome
	 */
	private static FormalTrialLedger.Outcome outcome(String operationId, String familyId, String promptTestId,
		FormalTrialLedger.Verdict verdict, boolean evaluated)
	{
		Optional<String> evaluatorEvidence = Optional.empty();
		if (evaluated)
			evaluatorEvidence = Optional.of("evaluator-" + operationId);
		return new FormalTrialLedger.Outcome(operationId, familyId, promptTestId, verdict, fingerprint('e'),
			evaluatorEvidence.map(ignored -> fingerprint('f')), Instant.parse("2026-09-01T12:00:00Z"));
	}

	/**
	 * Creates a lowercase SHA-256 fixture value.
	 *
	 * @param value the repeated hexadecimal character
	 * @return the 64-character fingerprint
	 */
	private static String fingerprint(char value)
	{
		return Character.toString(value).repeat(64);
	}
}
