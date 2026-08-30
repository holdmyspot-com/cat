package io.github.cowwoc.cat.common.evaluation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests paired fixed-budget live-worker coordination.
 */
public final class BenchmarkExecutionTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public BenchmarkExecutionTest()
	{
	}

	/**
	 * Closes the scope that owns this test class's mapper.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies a retained pair with unequal claims is rejected before either batch runs.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*retained different trial claims.*")
	public void rejectsMismatchedClaims() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup, scope.json());
			fixture.access.claims.put(fixture.candidateRun, List.of(2));

			fixture.execution.runFixedBenchmarkRound(fixture.baselineRun, fixture.baselineWorkspace, fixture.plan,
				fixture.candidateRun, fixture.candidateWorkspace, fixture.plan, 1, 2, null);
		}
	}

	/**
	 * Verifies an active evaluator ownership marker prevents retained batch recovery.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*another evaluator owns its round.*")
	public void refusesUnsafeRecovery() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup, scope.json());
			Files.createDirectories(fixture.baselineWorkspace);
			Files.writeString(fixture.baselineWorkspace.resolve(".cat-prompt-evaluation-evidence"),
				fixture.baselineRun.toAbsolutePath().normalize().toString(), UTF_8);
			fixture.access.activeEvaluationOwner = true;

			fixture.execution.runFixedBenchmarkRound(fixture.baselineRun, fixture.baselineWorkspace, fixture.plan,
				fixture.candidateRun, fixture.candidateWorkspace, fixture.plan, 1, 2, null);
		}
	}

	/**
	 * Verifies complete retained evidence bypasses both candidate batch executions.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void bypassesCompletedRound() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup, scope.json());
			fixture.access.complete = true;

			fixture.execution.runFixedBenchmarkRound(fixture.baselineRun, fixture.baselineWorkspace, fixture.plan,
				fixture.candidateRun, fixture.candidateWorkspace, fixture.plan, 1, 2, null);

			requireThat(fixture.access.batchCapacities, "batchCapacities").isEmpty();
		}
	}

	/**
	 * Verifies one worker runs the two candidates sequentially with the complete capacity.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void runsSequentialPairWithSingleWorker() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup, scope.json());

			fixture.execution.runFixedBenchmarkRound(fixture.baselineRun, fixture.baselineWorkspace, fixture.plan,
				fixture.candidateRun, fixture.candidateWorkspace, fixture.plan, 1, 1, null);

			requireThat(fixture.access.batchCapacities, "batchCapacities").isEqualTo(List.of(1, 1));
		}
	}

	/**
	 * Verifies multiple workers partition shared pair capacity without exceeding it.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void splitsPairCapacityBetweenCandidates() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup, scope.json());

			fixture.execution.runFixedBenchmarkRound(fixture.baselineRun, fixture.baselineWorkspace, fixture.plan,
				fixture.candidateRun, fixture.candidateWorkspace, fixture.plan, 1, 3, null);

			requireThat(fixture.access.batchCapacities.stream().sorted().toList(), "batchCapacities").
				isEqualTo(List.of(1, 2));
		}
	}

	/**
	 * Verifies a candidate batch failure remains the observable paired-round failure.
	 *
	 * @throws IOException if fixture I/O fails as expected
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*batch failure.*")
	public void preservesBatchFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup, scope.json());
			fixture.access.batchFailure = new IOException("batch failure");

			fixture.execution.runFixedBenchmarkRound(fixture.baselineRun, fixture.baselineWorkspace, fixture.plan,
				fixture.candidateRun, fixture.candidateWorkspace, fixture.plan, 1, 1, null);
		}
	}

	/**
	 * Verifies infrastructure evidence blocks a comparison and interruption retention remains best-effort.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesInfrastructureAndRecordsMarker() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup, scope.json());
			Files.createFile(fixture.baselineRun);
			fixture.access.infrastructureFailure = true;
			try
			{
				fixture.execution.refuseInfrastructureFailure(fixture.baselineRun, fixture.candidateRun);
				throw new AssertionError("Expected infrastructure failure");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failure").contains("prevented a valid comparison");
			}

			fixture.execution.recordInterruption(fixture.baselineRun, new IOException("interrupted pair"));
			Path marker = fixture.baselineRun.resolveSibling(fixture.baselineRun.getFileName() + ".interrupted");
			requireThat(Files.readString(marker, UTF_8), "marker").contains("interrupted pair");
		}
	}

	private static final class Fixture
	{
		private final TestAccess access;
		private final BenchmarkExecution execution;
		private final Path baselineRun;
		private final Path candidateRun;
		private final Path baselineWorkspace;
		private final Path candidateWorkspace;
		private final Path plan;

		/**
		 * Creates one direct live-worker fixture.
		 *
		 * @param access the controlled retained-record boundary
		 * @param baselineRun the baseline record path
		 * @param candidateRun the changed-candidate record path
		 * @param baselineWorkspace the baseline workspace path
		 * @param candidateWorkspace the changed-candidate workspace path
		 * @param plan the shared execution-plan path
		 */
		private Fixture(TestAccess access, Path baselineRun, Path candidateRun, Path baselineWorkspace,
			Path candidateWorkspace, Path plan)
		{
			this.access = access;
			this.execution = new BenchmarkExecution(access);
			this.baselineRun = baselineRun;
			this.candidateRun = candidateRun;
			this.baselineWorkspace = baselineWorkspace;
			this.candidateWorkspace = candidateWorkspace;
			this.plan = plan;
		}

		/**
		 * Creates a suite and retained record paths owned by the supplied cleanup scope.
		 *
		 * @param cleanup the test-owned temporary-directory scope
		 * @param json the scope-owned mapper that constructs fixture records
		 * @return the direct live-worker fixture
		 * @throws IOException if the fixture cannot be written
		 */
		private static Fixture create(TemporaryDirectories cleanup, JsonMapper json) throws IOException
		{
			Path root = cleanup.add(Files.createTempDirectory("benchmark-execution"));
			Path suite = root.resolve("suite");
			Files.createDirectories(suite.resolve("case-a"));
			Files.writeString(suite.resolve("suite.json"), "{}", UTF_8);
			Files.writeString(suite.resolve("case-a/scenario.md"), """
				## Scenario

				Evaluate the fixed benchmark.

				## Assertions

				### result

				Retain the fixed benchmark result.
				""", UTF_8);
			Path baselineRun = root.resolve("baseline.json");
			Path candidateRun = root.resolve("candidate.json");
			TestAccess access = new TestAccess(json, suite, baselineRun, candidateRun);
			return new Fixture(access, baselineRun, candidateRun, root.resolve("baseline-workspace"),
				root.resolve("candidate-workspace"), root.resolve("plan.json"));
		}
	}

	private static final class TestAccess implements BenchmarkExecution.Access
	{
		private final JsonMapper json;
		private final Map<Path, ObjectNode> records = new HashMap<>();
		private final Map<Path, List<Integer>> claims = new HashMap<>();
		private final List<Integer> batchCapacities = new ArrayList<>();
		private boolean complete;
		private boolean activeEvaluationOwner;
		private boolean infrastructureFailure;
		private IOException batchFailure;

		/**
		 * Creates controlled record and claim evidence for one paired round.
		 *
		 * @param json the scope-owned mapper that constructs fixture records
		 * @param suite the materialized suite path retained by both records
		 * @param baselineRun the baseline record path
		 * @param candidateRun the changed-candidate record path
		 */
		private TestAccess(JsonMapper json, Path suite, Path baselineRun, Path candidateRun)
		{
			this.json = json;
			records.put(baselineRun, record(json, suite));
			records.put(candidateRun, record(json, suite));
			claims.put(baselineRun, List.of(1));
			claims.put(candidateRun, List.of(1));
		}

		@Override
		public void validateMaximumConcurrency(int maximumConcurrency)
		{
		}

		@Override
		public void bindExecutionPlan(Path runPath, Path planPath)
		{
		}

		@Override
		public void ensureFixedBenchmarkRun(Path runPath)
		{
		}

		@Override
		public void bindMaximumConcurrency(Path runPath, int maximumConcurrency)
		{
		}

		@Override
		public List<Integer> claimFixedBenchmarkTrials(Path runPath, int count)
		{
			return claims.get(runPath);
		}

		@Override
		public List<Integer> trialsIn(Path workspace)
		{
			return List.of(1);
		}

		@Override
		public boolean hasActiveEvaluationOwner(Path marker)
		{
			return activeEvaluationOwner;
		}

		@Override
		public ArrayNode workerRecords(Path workspace)
		{
			return json.createArrayNode();
		}

		@Override
		public boolean hasNonterminalWorker(ArrayNode workers)
		{
			return false;
		}

		@Override
		public boolean hasActiveWorkerOwner(ArrayNode workers)
		{
			return false;
		}

		@Override
		public void terminalizeOwnerlessWorkers(Path workspace)
		{
		}

		@Override
		public JsonNode read(Path source)
		{
			return records.get(source);
		}

		@Override
		public ObjectNode object(JsonNode value, String description)
		{
			return (ObjectNode) value;
		}

		@Override
		public String text(ObjectNode value, String field, Path source)
		{
			return value.path(field).asString();
		}

		@Override
		public Path path(String value)
		{
			return Path.of(value);
		}

		@Override
		public ArrayNode array(JsonNode value, String description, Path source)
		{
			return (ArrayNode) value;
		}

		@Override
		public List<Integer> unrecordedTrials(Path runPath, String caseId, List<Integer> trials)
		{
			if (complete)
				return List.of();
			return trials;
		}

		@Override
		public void runFixedBenchmarkBatch(Path runPath, List<Integer> trials, Path workspace, Path planPath,
			int workerCapacity, HarnessEvaluationRunner evaluationRunner) throws IOException
		{
			if (batchFailure != null)
				throw batchFailure;
			batchCapacities.add(workerCapacity);
		}

		@Override
		public boolean hasInfrastructureFailure(Path runPath)
		{
			return infrastructureFailure;
		}

		/**
		 * Creates the retained record content needed to materialize the suite's cases.
		 *
		 * @param json the scope-owned mapper that constructs the retained record
		 * @param suite the suite path retained in the record
		 * @return the controlled record content
		 */
		private static ObjectNode record(JsonMapper json, Path suite)
		{
			return json.createObjectNode().put("suite", suite.toString());
		}
	}
}
