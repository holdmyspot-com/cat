package io.github.cowwoc.cat.common.optimization.certification;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests the evidence-binding boundary of the family-sequential engine.
 */
public final class FamilySequentialEngineTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public FamilySequentialEngineTest()
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
	 * Verifies the engine schedules from evidence bound to the candidate's retained lineage allowance.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void decidesFromBoundAllowanceEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("family-sequential-engine-"));
			Path allowances = allowances(root);
			CandidateAllowanceLedger.Assignment assignment = CandidateAllowanceLedger.assign(scope.json(), allowances,
				"candidate-a", Instant.parse("2026-09-01T12:00:00Z"));
			Path trials = trials(root, assignment.entryFingerprint());

			FamilySequentialDecision.Result result = FamilySequentialEngine.decide(scope.json(), allowances, trials,
				new FamilySequentialDecision.Policy(0.95, 300));

			requireThat(result.action(), "action").isEqualTo(FamilySequentialDecision.Action.SAMPLE_FAMILY);
			requireThat(result.familyId().orElseThrow(), "familyId").isEqualTo("family-a");
		}
	}

	/**
	 * Verifies formal evidence cannot be interpreted using another allowance entry.
	 *
	 * @throws IOException for the expected binding failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Formal trial ledger does not match the candidate allowance entry.*")
	public void rejectsAnotherAllowanceEntry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("family-sequential-engine-"));
			Path allowances = allowances(root);
			CandidateAllowanceLedger.assign(scope.json(), allowances, "candidate-a",
				Instant.parse("2026-09-01T12:00:00Z"));
			Path trials = trials(root, fingerprint('f'));

			FamilySequentialEngine.decide(scope.json(), allowances, trials,
				new FamilySequentialDecision.Policy(0.95, 300));
		}
	}

	/**
	 * Creates the lineage allowance ledger.
	 *
	 * @param root the fixture root
	 * @return the allowance manifest
	 * @throws IOException if initialization fails
	 */
	private Path allowances(Path root) throws IOException
	{
		Path manifest = root.resolve("allowances.json");
		CandidateAllowanceLedger.initialize(scope.json(), manifest, "lineage-a", new BigDecimal("0.05"),
			CandidateAllowanceLedger.Schedule.HALVING_V1);
		return manifest;
	}

	/**
	 * Creates the frozen formal trial ledger.
	 *
	 * @param root the fixture root
	 * @param allowanceEntryFingerprint the allowance entry bound to the formal ledger
	 * @return the formal manifest
	 * @throws IOException if initialization fails
	 */
	private Path trials(Path root, String allowanceEntryFingerprint) throws IOException
	{
		Path manifest = root.resolve("trials.json");
		FormalTrialLedger.Identity identity = new FormalTrialLedger.Identity("candidate-a", fingerprint('a'),
			fingerprint('b'), fingerprint('c'), fingerprint('d'), fingerprint('e'), allowanceEntryFingerprint);
		FormalTrialLedger.initialize(scope.json(), manifest, identity, List.of("family-a", "family-b"));
		return manifest;
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
