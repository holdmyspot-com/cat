package io.github.cowwoc.cat.common.optimization.certification;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests the strict versioned family-sequential policy document.
 */
public final class FamilySequentialPolicyDocumentTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public FamilySequentialPolicyDocumentTest()
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
	 * Verifies a supported policy is parsed into closed statistical choices and receives a reproducible fingerprint.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readsSupportedPolicy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("family-policy-"));
			Path policy = Files.writeString(root.resolve("policy.json"), policyJson(""));

			FamilySequentialPolicyDocument.Loaded loaded = FamilySequentialPolicyDocument.read(scope.json(), policy);

			requireThat(loaded.policy().identifier(), "identifier").isEqualTo("family-sequential-v1");
			requireThat(loaded.policy().decisionPolicy().target(), "target").isEqualTo(0.95);
			requireThat(loaded.policy().decisionPolicy().maximumTrialsPerFamily(), "maximumTrialsPerFamily").
				isEqualTo(1_000);
			requireThat(loaded.policy().candidateAllowanceSchedule(), "candidateAllowanceSchedule").isEqualTo(
				CandidateAllowanceLedger.Schedule.HALVING_V1);
			requireThat(loaded.fingerprint().length(), "fingerprintLength").isEqualTo(64);
			requireThat(FamilySequentialPolicyDocument.read(scope.json(), policy).fingerprint(), "repeatFingerprint").
				isEqualTo(loaded.fingerprint());
		}
	}

	/**
	 * Verifies an unknown field cannot silently extend the frozen policy.
	 *
	 * @throws IOException for the expected schema failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*policy has unsupported or missing fields.*")
	public void rejectsUnknownField() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("family-policy-"));
			Path policy = Files.writeString(root.resolve("policy.json"), policyJson(
				",\"runtime_override\":true"));

			FamilySequentialPolicyDocument.read(scope.json(), policy);
		}
	}

	/**
	 * Verifies a schedule outside the policy's closed domain is rejected at the document boundary.
	 *
	 * @throws IOException for the expected domain failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported candidate allowance schedule: user-selected.*")
	public void rejectsUnknownSchedule() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("family-policy-"));
			Path policy = Files.writeString(root.resolve("policy.json"),
				policyJson("").replace("halving-v1", "user-selected"));

			FamilySequentialPolicyDocument.read(scope.json(), policy);
		}
	}

	/**
	 * Creates one policy document with an optional extra root fragment.
	 *
	 * @param extraField the fragment to append before the root closes
	 * @return the JSON document
	 */
	static String policyJson(String extraField)
	{
		return """
			{
			  "schema_version":"family-sequential-policy-v1",
			  "identifier":"family-sequential-v1",
			  "statistical_revision":"uniform-mixture-anytime-v1",
			  "target":"0.95",
			  "maximum_trials_per_family":1000,
			  "lineage_error_budget":"0.05",
			  "candidate_allowance_schedule":"halving-v1"%s
			}
			""".formatted(extraField);
	}
}
