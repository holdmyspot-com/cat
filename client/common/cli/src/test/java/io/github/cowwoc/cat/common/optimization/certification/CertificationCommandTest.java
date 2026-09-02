package io.github.cowwoc.cat.common.optimization.certification;


import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests the packaged family-sequential certification workflow.
 */
public final class CertificationCommandTest
{
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public CertificationCommandTest()
	{
	}

	/**
	 * Closes the JSON scope after the tests finish.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies the command binds a candidate to its preassigned allowance and reaches a certified result through fresh
	 * formal outcomes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void certifiesBoundFormalEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("certification-command-"));
			Path allowances = root.resolve("allowances.json");
			Path trials = root.resolve("trials.json");
			Path policy = Files.writeString(root.resolve("policy.json"),
				FamilySequentialPolicyDocumentTest.policyJson(""));

			run("initialize-lineage", allowances.toString(), "lineage-a", policy.toString());
			String assignment = run("assign", allowances.toString(), "candidate-a");
			requireThat(assignment, "assignment").contains("candidate=candidate-a").contains("allowance=0.025");
			run("initialize-candidate", trials.toString(), allowances.toString(), "candidate-a", fingerprint('a'),
				fingerprint('b'), fingerprint('c'), fingerprint('d'), policy.toString(), "family-a");

			int passingTrials = Statistics.minimumPassingTrials(1_000, 0.025, 0.95);
			if (passingTrials > 1_000)
				throw new AssertionError("Fixture policy cannot certify within its declared cap");
			for (int trial = 1; trial <= passingTrials; ++trial)
			{
				FormalTrialLedger.record(scope.json(), trials, new FormalTrialLedger.Outcome("operation-" + trial,
					"family-a", "scenario-" + trial, FormalTrialLedger.Verdict.PASS, fingerprint('d'),
					Optional.of(fingerprint('e')), FIXED_CLOCK.instant().plusSeconds(trial)));
			}

			String report = run("decide", allowances.toString(), trials.toString(), policy.toString());

			requireThat(report, "report").contains("action=certified").contains("Current lower bound: 99.4%").
				contains("Target: above 95%");
		}
	}

	/**
	 * Verifies the public command rejects an attempt to inject a user stop into automatic certification.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Usage: decide.*")
	public void rejectsUserStop()
	{
		run("decide", "allowances.json", "trials.json", "policy.json", "--stop");
	}

	/**
	 * Verifies the public command rejects an attempt to pause automatic certification.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Usage: decide.*")
	public void rejectsUserPause()
	{
		run("decide", "allowances.json", "trials.json", "policy.json", "--paused");
	}

	/**
	 * Verifies formal evidence cannot be reinterpreted under a changed target or trial cap.
	 *
	 * @throws IOException for the expected policy-binding failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Formal trial ledger does not match the family-sequential policy.*")
	public void rejectsChangedStatisticalPolicy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("certification-command-"));
			Path allowances = root.resolve("allowances.json");
			Path trials = root.resolve("trials.json");
			Path policy = Files.writeString(root.resolve("policy.json"),
				FamilySequentialPolicyDocumentTest.policyJson(""));
			Path changedPolicy = Files.writeString(root.resolve("changed-policy.json"),
				FamilySequentialPolicyDocumentTest.policyJson("").replace("1000", "999"));
			run("initialize-lineage", allowances.toString(), "lineage-a", policy.toString());
			run("assign", allowances.toString(), "candidate-a");
			run("initialize-candidate", trials.toString(), allowances.toString(), "candidate-a", fingerprint('a'),
				fingerprint('b'), fingerprint('c'), fingerprint('d'), policy.toString(), "family-a");

			CertificationCommand.run(scope.json(), FIXED_CLOCK, new String[]{"decide", allowances.toString(),
				trials.toString(), changedPolicy.toString()}, new StringBuilder());
		}
	}

	/**
	 * Verifies the untyped command boundary rejects a command outside its closed command set.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported certification command: certify-everything.*")
	public void rejectsUnknownCommand()
	{
		run("certify-everything");
	}

	/**
	 * Verifies a caller cannot inject a formal verdict and evidence hashes through the public command.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported certification command: record.*")
	public void rejectsCallerSuppliedFormalOutcome()
	{
		run("record");
	}

	/**
	 * Runs one command with the fixture clock.
	 *
	 * @param args the raw command arguments
	 * @return the command report
	 */
	private String run(String... args)
	{
		StringBuilder report = new StringBuilder();
		try
		{
			CertificationCommand.run(scope.json(), FIXED_CLOCK, args, report);
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
		return report.toString();
	}

	/**
	 * Creates one lowercase SHA-256 fixture.
	 *
	 * @param value the repeated hexadecimal character
	 * @return the 64-character fingerprint
	 */
	private static String fingerprint(char value)
	{
		return Character.toString(value).repeat(64);
	}
}
