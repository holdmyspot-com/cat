package io.github.cowwoc.cat.common.optimization.development;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests durable prompt-test-pool identity reservations.
 */
public final class PromptTestIdentityLedgerTest
{
	private static final String CONTRACT_SHA =
		"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public PromptTestIdentityLedgerTest()
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
	 * Verifies an exact retry returns the retained reservation without appending another identity.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsExactRetry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt_test-identities-"));
			Path ledger = root.resolve("identities.jsonl");
			PromptTestIdentityLedger.initialize(scope.json(), ledger, CONTRACT_SHA);
			PromptTestPoolPartition.Identity promptTest = promptTest(PromptTestPoolPartition.Pool.DEVELOPMENT,
				"prompt_test-a", "operation-a", "content-a");

			PromptTestIdentityLedger.Reservation first = PromptTestIdentityLedger.reserve(scope.json(), ledger, promptTest);
			PromptTestIdentityLedger.Reservation retry = PromptTestIdentityLedger.reserve(scope.json(), ledger, promptTest);

			requireThat(first.status(), "firstStatus").isEqualTo(PromptTestIdentityLedger.Status.RESERVED);
			requireThat(retry.status(), "retryStatus").isEqualTo(PromptTestIdentityLedger.Status.RETAINED);
			requireThat(PromptTestIdentityLedger.read(scope.json(), ledger).identities().size(), "identityCount").
				isEqualTo(1);
		}
	}

	/**
	 * Verifies generated content cannot be reserved by a second evidence pool.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void blocksCrossPoolReservation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt_test-collision-"));
			Path ledger = root.resolve("identities.jsonl");
			PromptTestIdentityLedger.initialize(scope.json(), ledger, CONTRACT_SHA);
			PromptTestPoolPartition.Identity development = promptTest(PromptTestPoolPartition.Pool.DEVELOPMENT,
				"development-a", "development-operation-a", "shared-content");
			PromptTestPoolPartition.Identity formal = promptTest(PromptTestPoolPartition.Pool.FORMAL, "formal-a",
				"formal-operation-a", "shared-content");
			PromptTestIdentityLedger.reserve(scope.json(), ledger, development);

			PromptTestIdentityLedger.Reservation result = PromptTestIdentityLedger.reserve(scope.json(), ledger, formal);

			requireThat(result.status(), "status").isEqualTo(PromptTestIdentityLedger.Status.BLOCKED);
			requireThat(result.collisions().size(), "collisionCount").isEqualTo(1);
			requireThat(PromptTestIdentityLedger.read(scope.json(), ledger).identities().size(), "identityCount").
				isEqualTo(1);
		}
	}

	/**
	 * Verifies an operation identifier cannot be reassigned to changed prompt-test input.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*operation identifier already reserves different prompt-test input.*")
	public void rejectsChangedOperationRetry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt_test-operation-"));
			Path ledger = root.resolve("identities.jsonl");
			PromptTestIdentityLedger.initialize(scope.json(), ledger, CONTRACT_SHA);
			PromptTestIdentityLedger.reserve(scope.json(), ledger, promptTest(PromptTestPoolPartition.Pool.DEVELOPMENT,
				"prompt_test-a", "operation-a", "content-a"));

			PromptTestIdentityLedger.reserve(scope.json(), ledger, promptTest(PromptTestPoolPartition.Pool.DEVELOPMENT,
				"prompt_test-b", "operation-a", "content-b"));
		}
	}

	/**
	 * Creates one stable pool identity.
	 *
	 * @param pool the evidence pool
	 * @param instanceIdentifier the prompt-test instance
	 * @param operationIdentifier the execution identity
	 * @param content the rendered prompt-test content
	 * @return the prompt-test identity
	 * @throws IOException if the content identity cannot be calculated
	 */
	private static PromptTestPoolPartition.Identity promptTest(PromptTestPoolPartition.Pool pool,
		String instanceIdentifier,
		String operationIdentifier, String content) throws IOException
	{
		return new PromptTestPoolPartition.Identity(pool, instanceIdentifier, pool.name() + ":seed:" + instanceIdentifier,
			Fingerprints.ofUtf8(content), operationIdentifier);
	}
}
