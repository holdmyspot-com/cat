package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.optimization.OperationResultKind;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests durable paired development comparisons and best-candidate retention.
 */
public final class DevelopmentComparisonOperationTest
{
	private static final String CONTRACT_SHA =
		"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public DevelopmentComparisonOperationTest()
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
	 * Verifies a focused winner advances to an all-family comparison without replacing the retained best candidate.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void broadensFocusedWinnerBeforePromotion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-comparison-"));
			DevelopmentComparisonOperation.CandidateReference champion = candidate(root, "champion", "old");
			DevelopmentComparisonOperation.CandidateReference challenger = candidate(root, "challenger", "new");
			List<DevelopmentComparisonOperation.TrialRequest> invocations = new ArrayList<>();
			DevelopmentComparisonOperation.CandidateTrialExecutor executor = request ->
			{
				invocations.add(request);
				boolean passed = request.candidate().identifier().equals("challenger");
				return outcome(request, passed);
			};

			DevelopmentComparisonOperation.Result result = DevelopmentComparisonOperation.execute(scope.json(),
				request(root.resolve("focused"), champion, challenger,
					DevelopmentComparisonOperation.ComparisonScope.FOCUSED), executor);

			requireThat(result.action(), "action").
				isEqualTo(DevelopmentComparisonOperation.Action.BROADEN_COMPARISON);
			requireThat(result.registryResult(), "registryResult").
				isEqualTo(OperationResultKind.PROMOTE);
			requireThat(result.retainedBest().identifier(), "retainedBest").isEqualTo("champion");
			requireThat(invocations, "invocations").size().isEqualTo(200);
			for (int index = 0; index < invocations.size(); index += 2)
			{
				DevelopmentComparisonOperation.TrialRequest first = invocations.get(index);
				DevelopmentComparisonOperation.TrialRequest second = invocations.get(index + 1);
				requireThat(first.promptTest().instanceIdentifier(), "firstPromptTest").
					isEqualTo(second.promptTest().instanceIdentifier());
				requireThat(Set.of(first.candidate().identifier(), second.candidate().identifier()), "pairedCandidates").
					containsExactly(Set.of("champion", "challenger"));
				requireThat(first.position(), "firstPosition").isEqualTo(1);
				requireThat(second.position(), "secondPosition").isEqualTo(2);
			}
		}
	}

	/**
	 * Verifies only an all-family winner replaces the retained best candidate.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void promotesAllFamilyWinner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-comparison-"));
			DevelopmentComparisonOperation.CandidateReference champion = candidate(root, "champion", "old");
			DevelopmentComparisonOperation.CandidateReference challenger = candidate(root, "challenger", "new");

			DevelopmentComparisonOperation.Result result = DevelopmentComparisonOperation.execute(scope.json(),
				request(root.resolve("broad"), champion, challenger,
					DevelopmentComparisonOperation.ComparisonScope.ALL_FAMILIES),
				request -> outcome(request, request.candidate().identifier().equals("challenger")));

			requireThat(result.action(), "action").isEqualTo(DevelopmentComparisonOperation.Action.PROMOTE);
			requireThat(result.retainedBest().identifier(), "retainedBest").isEqualTo("challenger");
			requireThat(result.familyPairs().getFirst().challengerWins(), "challengerWins").isEqualTo(100);
		}
	}

	/**
	 * Verifies a terminal retry returns retained evidence without repeating a candidate trial.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retryDoesNotRepeatTrials() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-comparison-"));
			DevelopmentComparisonOperation.CandidateReference champion = candidate(root, "champion", "old");
			DevelopmentComparisonOperation.CandidateReference challenger = candidate(root, "challenger", "new");
			DevelopmentComparisonOperation.Request request = request(root.resolve("retry"), champion, challenger,
				DevelopmentComparisonOperation.ComparisonScope.ALL_FAMILIES);
			AtomicInteger invocations = new AtomicInteger();
			DevelopmentComparisonOperation.CandidateTrialExecutor executor = trial ->
			{
				invocations.incrementAndGet();
				return outcome(trial, trial.candidate().identifier().equals("challenger"));
			};

			DevelopmentComparisonOperation.Result first = DevelopmentComparisonOperation.execute(scope.json(), request,
				executor);
			DevelopmentComparisonOperation.Result second = DevelopmentComparisonOperation.execute(scope.json(), request,
				executor);

			requireThat(invocations.get(), "trialInvocations").isEqualTo(200);
			requireThat(second, "retainedResult").isEqualTo(first);
		}
	}

	/**
	 * Verifies a named material regression retains the prior best candidate.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsChampionForMaterialRegression() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-comparison-"));
			DevelopmentComparisonOperation.CandidateReference champion = candidate(root, "champion", "old");
			DevelopmentComparisonOperation.CandidateReference challenger = candidate(root, "challenger", "new");

			DevelopmentComparisonOperation.Result result = DevelopmentComparisonOperation.execute(scope.json(),
				request(root.resolve("regression"), champion, challenger,
					DevelopmentComparisonOperation.ComparisonScope.ALL_FAMILIES),
				request -> outcome(request, request.candidate().identifier().equals("champion")));

			requireThat(result.action(), "action").
				isEqualTo(DevelopmentComparisonOperation.Action.REPAIR_REGRESSION);
			requireThat(result.affectedFamilies(), "affectedFamilies").containsExactly(List.of("clarity"));
			requireThat(result.retainedBest().identifier(), "retainedBest").isEqualTo("champion");
		}
	}

	/**
	 * Rejects execution before paired trials when the challenger has no verifiable allowance assignment.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsMissingPromotionAllowance() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("development-comparison-"));
			DevelopmentComparisonOperation.CandidateReference champion = candidate(root, "champion", "old");
			DevelopmentComparisonOperation.CandidateReference challenger = candidate(root, "challenger", "new");
			DevelopmentComparisonOperation.Request request = request(root.resolve("unassigned"), champion, challenger,
				DevelopmentComparisonOperation.ComparisonScope.ALL_FAMILIES);
			Files.delete(request.promotionAllowanceManifest());

			requireIo(() -> DevelopmentComparisonOperation.execute(scope.json(), request,
				trial -> outcome(trial, true)));
		}
	}

	/**
	 * Creates one complete comparison request.
	 *
	 * @param operationRoot the durable operation root
	 * @param champion the retained best candidate
	 * @param challenger the proposed replacement
	 * @param comparisonScope the comparison stage
	 * @return the request
	 * @throws IOException if the identity ledger cannot be initialized
	 */
	private DevelopmentComparisonOperation.Request request(Path operationRoot,
		DevelopmentComparisonOperation.CandidateReference champion,
		DevelopmentComparisonOperation.CandidateReference challenger,
		DevelopmentComparisonOperation.ComparisonScope comparisonScope) throws IOException
	{
		PromptTestGenerationPolicy promptTestPolicy = promptTestPolicy();
		Path identityLedger = operationRoot.resolveSibling(operationRoot.getFileName() + "-identities.jsonl");
		Path promotionLedger = operationRoot.resolveSibling(operationRoot.getFileName() + "-promotion.json");
		PromptTestIdentityLedger.initialize(scope.json(), identityLedger, CONTRACT_SHA);
		PromotionAllowanceLedger.initialize(scope.json(), promotionLedger, "development-lineage");
		PromotionAllowanceLedger.assign(scope.json(), promotionLedger, challenger.identifier(),
			java.time.Instant.parse("2026-09-01T12:00:00Z"));
		return new DevelopmentComparisonOperation.Request(operationRoot, identityLedger, promotionLedger, CONTRACT_SHA,
			"comparison-1",
			policy(), champion, challenger, comparisonScope, Set.of("clarity"), List.of(promptTestPolicy),
			promptTests(promptTestPolicy), "order-seed");
	}

	/**
	 * Creates the executable development policy.
	 *
	 * @return the policy
	 */
	private static DevelopmentPolicy policy()
	{
		return new DevelopmentPolicy("development-v1", 8, 8, 128, 1, 1, 2, 2, 0, 2, 0.01);
	}

	/**
	 * Creates enough fixed paired promptTests for a 2.5% allowance to establish a directional lower bound.
	 *
	 * @param policy the generator that owns the prompt-test family
	 * @return the frozen paired promptTests
	 */
	private static List<PromptTestGenerationPolicy.PromptTest> promptTests(PromptTestGenerationPolicy policy)
	{
		List<PromptTestGenerationPolicy.PromptTest> result = new ArrayList<>();
		for (int seed = 1; seed <= 100; ++seed)
			result.add(policy.generate(seed));
		return List.copyOf(result);
	}

	/**
	 * Creates one finite family generator.
	 *
	 * @return the generator
	 */
	private static PromptTestGenerationPolicy promptTestPolicy()
	{
		return new PromptTestGenerationPolicy(PromptTestPoolPartition.Pool.DEVELOPMENT, "clarity", "distribution-v1",
			"uniform-v1", "generator-v1", "development", "Explain in a {{tone}} tone.",
			Map.of("tone", List.of("plain", "direct", "brief")), Set.of(), 10);
	}

	/**
	 * Creates one immutable candidate bundle reference.
	 *
	 * @param root the fixture root
	 * @param identifier the candidate identifier
	 * @param content the primary rule content
	 * @return the reference
	 * @throws IOException if the bundle cannot be materialized
	 */
	private DevelopmentComparisonOperation.CandidateReference candidate(Path root, String identifier, String content)
		throws IOException
	{
		Path source = Files.createDirectories(root.resolve(identifier + "-source/common"));
		Files.writeString(source.resolve("rule.md"), content);
		Path bundle = root.resolve(identifier + "-bundle");
		CandidateBundle.Candidate materialized = CandidateBundle.materialize(scope.json(), source.getParent(), bundle,
			"common/rule.md", List.of());
		return new DevelopmentComparisonOperation.CandidateReference(identifier, bundle, materialized.sha256());
	}

	/**
	 * Creates one retained fake trial outcome.
	 *
	 * @param request the trial request
	 * @param passed whether the candidate passed
	 * @return the outcome
	 */
	private static DevelopmentComparisonOperation.TrialOutcome outcome(
		DevelopmentComparisonOperation.TrialRequest request, boolean passed)
	{
		return new DevelopmentComparisonOperation.TrialOutcome(passed,
			request.idempotencyKey() + "-session", "a".repeat(64), 10, 5, 100);
	}

	/**
	 * Verifies an I/O operation fails.
	 *
	 * @param operation the operation expected to fail
	 * @throws IOException if the operation unexpectedly succeeds
	 */
	private static void requireIo(IoOperation operation) throws IOException
	{
		try
		{
			operation.run();
		}
		catch (IOException _)
		{
			return;
		}
		throw new IOException("Expected IOException");
	}

	/**
	 * Declares one I/O operation used by assertion helpers.
	 */
	@FunctionalInterface
	private interface IoOperation
	{
		/**
		 * Runs the operation.
		 *
		 * @throws IOException if execution fails
		 */
		void run() throws IOException;
	}
}
