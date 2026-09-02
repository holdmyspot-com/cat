package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.optimization.OperationResultKind;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests durable readiness, plateau, conflict, and spending decisions.
 */
public final class DevelopmentReadinessOperationTest
{
	private static final String CONTRACT_SHA =
		"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public DevelopmentReadinessOperationTest()
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
	 * Verifies fresh readiness evidence and a certify-now cost decision start formal evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void startsFormalBatch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-readiness-"));

			DevelopmentReadinessOperation.Result result = DevelopmentReadinessOperation.execute(scope.json(),
				request(root, DevelopmentReadinessOperation.ForecastChoice.CERTIFY_NOW, List.of(0.80, 0.82), Set.of(),
					true));

			requireThat(result.action(), "action").isEqualTo(DevelopmentReadinessOperation.Action.START_FORMAL_BATCH);
			requireThat(result.registryResult(), "registryResult").
				isEqualTo(OperationResultKind.START_FORMAL_BATCH);
			requireThat(result.costForecastAvailable(), "costForecastAvailable").isTrue();
		}
	}

	/**
	 * Verifies the cost policy may select one more development round even after the empirical readiness gate passes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void continuesForLowerDevelopmentCost() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-readiness-"));

			DevelopmentReadinessOperation.Result result = DevelopmentReadinessOperation.execute(scope.json(),
				request(root, DevelopmentReadinessOperation.ForecastChoice.ONE_MORE_DEVELOPMENT_ROUND,
					List.of(0.80, 0.82), Set.of(), true));

			requireThat(result.action(), "action").isEqualTo(DevelopmentReadinessOperation.Action.CONTINUE_DEVELOPMENT);
		}
	}

	/**
	 * Verifies plateau is terminal for a weak readiness batch and retains the current best candidate.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void detectsPlateauBeforeNextRound() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-readiness-"));

			DevelopmentReadinessOperation.Result result = DevelopmentReadinessOperation.execute(scope.json(),
				request(root, DevelopmentReadinessOperation.ForecastChoice.UNAVAILABLE,
					List.of(0.80, 0.801, 0.802), Set.of(), false));

			requireThat(result.action(), "action").isEqualTo(DevelopmentReadinessOperation.Action.PLATEAU);
			requireThat(result.retainedBest().identifier(), "retainedBest").isEqualTo("champion");
			requireThat(result.costForecastAvailable(), "costForecastAvailable").isFalse();
		}
	}

	/**
	 * Verifies named prompt-system conflicts take precedence over another target-only development round.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void requestsNamedConflictRepair() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-readiness-"));

			DevelopmentReadinessOperation.Result result = DevelopmentReadinessOperation.execute(scope.json(),
				request(root, DevelopmentReadinessOperation.ForecastChoice.CERTIFY_NOW, List.of(0.80, 0.82),
					Set.of("rules/architecture.md"), true));

			requireThat(result.action(), "action").isEqualTo(DevelopmentReadinessOperation.Action.REPAIR_NAMED_CONFLICT);
			requireThat(result.namedConflicts(), "namedConflicts").containsExactly(List.of("rules/architecture.md"));
		}
	}

	/**
	 * Verifies completed readiness labels cannot introduce promptTests that were not reserved before inference.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*no pre-inference prompt-test reservation.*")
	public void rejectsUnreservedReadinessTrial() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-readiness-unreserved-"));
			DevelopmentReadinessOperation.Request retained = request(root,
				DevelopmentReadinessOperation.ForecastChoice.CERTIFY_NOW, List.of(0.80, 0.82), Set.of(), true);
			Path emptyLedger = root.resolve("empty-identities.jsonl");
			PromptTestIdentityLedger.initialize(scope.json(), emptyLedger, CONTRACT_SHA);
			DevelopmentReadinessOperation.Request unreserved = new DevelopmentReadinessOperation.Request(
				retained.resultPath(), emptyLedger, retained.contractFingerprint(), retained.idempotencyKey(),
				retained.policy(), retained.retainedBest(), retained.allFamilyIdentifiers(), retained.trials(),
				retained.comparableScores(), retained.namedConflicts(), retained.forecastChoice());

			DevelopmentReadinessOperation.execute(scope.json(), unreserved);
		}
	}

	/**
	 * Creates one complete readiness request.
	 *
	 * @param root the fixture root
	 * @param forecastChoice the frozen cost-policy choice
	 * @param scores comparable development scores
	 * @param conflicts unresolved named conflicts
	 * @param readinessPass whether every readiness trial passes
	 * @return the request
	 * @throws IOException if the candidate cannot be materialized
	 */
	private DevelopmentReadinessOperation.Request request(Path root,
		DevelopmentReadinessOperation.ForecastChoice forecastChoice, List<Double> scores, Set<String> conflicts,
		boolean readinessPass) throws IOException
	{
		DevelopmentComparisonOperation.CandidateReference champion = candidate(root);
		PromptTestPoolPartition.Identity development = identity(PromptTestPoolPartition.Pool.DEVELOPMENT, "development", 1);
		PromptTestPoolPartition.Identity first = identity(PromptTestPoolPartition.Pool.READINESS, "readiness", 2);
		PromptTestPoolPartition.Identity second = identity(PromptTestPoolPartition.Pool.READINESS, "readiness", 3);
		List<DevelopmentReadinessOperation.Trial> trials = List.of(
			new DevelopmentReadinessOperation.Trial("clarity", first, readinessPass),
			new DevelopmentReadinessOperation.Trial("clarity", second, readinessPass));
		Path identityLedger = root.resolve("identities.jsonl");
		PromptTestIdentityLedger.initialize(scope.json(), identityLedger, CONTRACT_SHA);
		for (PromptTestPoolPartition.Identity identity : List.of(development, first, second))
			PromptTestIdentityLedger.reserve(scope.json(), identityLedger, identity);
		return new DevelopmentReadinessOperation.Request(root.resolve("readiness.json"), identityLedger, CONTRACT_SHA,
			"readiness-1", policy(), champion, Set.of("clarity"), trials, scores, conflicts, forecastChoice);
	}

	/**
	 * Creates the readiness policy.
	 *
	 * @return the policy
	 */
	private static DevelopmentPolicy policy()
	{
		return new DevelopmentPolicy("development-v1", 2, 1, 3, 1, 1, 2, 2, 0, 2, 0.01);
	}

	/**
	 * Creates one immutable champion.
	 *
	 * @param root the fixture root
	 * @return the candidate reference
	 * @throws IOException if the bundle cannot be created
	 */
	private DevelopmentComparisonOperation.CandidateReference candidate(Path root) throws IOException
	{
		Path source = Files.createDirectories(root.resolve("source/common"));
		Files.writeString(source.resolve("rule.md"), "best");
		Path bundle = root.resolve("candidate");
		CandidateBundle.Candidate candidate = CandidateBundle.materialize(scope.json(), source.getParent(), bundle,
			"common/rule.md", List.of());
		return new DevelopmentComparisonOperation.CandidateReference("champion", bundle, candidate.sha256());
	}

	/**
	 * Creates one pool identity with unique values.
	 *
	 * @param pool the evidence pool
	 * @param prefix the identity prefix
	 * @param index the identity suffix
	 * @return the prompt_test identity
	 * @throws IOException if its fingerprint cannot be calculated
	 */
	private static PromptTestPoolPartition.Identity identity(PromptTestPoolPartition.Pool pool, String prefix, int index)
		throws IOException
	{
		String value = prefix + "-" + index;
		return new PromptTestPoolPartition.Identity(pool, value, value + "-seed", Fingerprints.ofUtf8(value),
			value + "-execution");
	}
}
