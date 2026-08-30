package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests bounded capability-diagnostic lifecycle behavior.
 */
public final class CapabilityDiagnosticTest
{
	/**
	 * Creates the test fixture.
	 */
	public CapabilityDiagnosticTest()
	{
	}

	/**
	 * Verifies a changed profile reserves the fixed non-certifying diagnostic contract.
	 *
	 * @throws IOException if the retained record cannot be updated
	 */
	@Test
	public void reservesChangedProfile() throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			TestAccess access = new TestAccess(scope.json());
			access.run.put("purpose", "diagnostic");
			CapabilityDiagnostic diagnostic = new CapabilityDiagnostic(access);

			diagnostic.start(Path.of("run.json"), new CapabilityDiagnostic.Profile("target"),
				new CapabilityDiagnostic.Profile("changed"));

			ObjectNode reserved = (ObjectNode) access.run.path("profile_capability_diagnostic");
			requireThat(access.run.path("mode").asString(), "mode").isEqualTo("profile_capability_diagnostic");
			requireThat(reserved.path("trial_count").asInt(), "trialCount").isEqualTo(3);
			requireThat(reserved.path("target_executor_profile").asString(), "target").isEqualTo("target");
			requireThat(reserved.path("diagnostic_executor_profile").asString(), "changed").isEqualTo("changed");
		}
	}

	/**
	 * Verifies a completed diagnostic cannot execute a second time.
	 *
	 * @throws IOException if the expected refusal cannot be evaluated
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*Profile-capability diagnostic already completed.*")
	public void refusesCompletedResult() throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			TestAccess access = new TestAccess(scope.json());
			access.run.put("mode", "profile_capability_diagnostic");
			access.run.putObject("profile_capability_diagnostic").putObject("result");

			new CapabilityDiagnostic(access).execute(Path.of("run.json"), Path.of("workspace"), Path.of("plan.json"), 1,
				null);
		}
	}

	/**
	 * Verifies a terminal diagnostic reports counts and infrastructure state without compliance output.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void projectsCountOnlyTerminalResult() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("capability-diagnostic"));
			Path suite = root.resolve("suite");
			Files.createDirectories(suite);
			Files.writeString(suite.resolve("suite.json"), "{\"diagnostic_kind\":\"direct_application\"}");
			TestAccess access = new TestAccess(scope.json());
			access.run.put("suite", suite.toString());
			ObjectNode reservation = access.run.putObject("profile_capability_diagnostic");
			reservation.put("target_executor_profile", "target");
			reservation.put("diagnostic_executor_profile", "changed");
			reservation.put("validator_profile", "independent-target");
			access.counts.put("passed", 2).put("failed", 1);
			access.infrastructureFailure = true;

			ObjectNode result = new CapabilityDiagnostic(access).result(Path.of("run.json"), "case-a",
				root.resolve("workspace"), List.of(1, 2, 3));

			requireThat(result.path("completed_trials").asInt(), "completedTrials").isEqualTo(3);
			requireThat(result.path("passed_trials").asInt(), "passedTrials").isEqualTo(2);
			requireThat(result.path("failed_trials").asInt(), "failedTrials").isEqualTo(1);
			requireThat(result.path("infrastructure_failed").asBoolean(), "infrastructureFailure").isTrue();
			requireThat(result.has("compliance_rate"), "complianceRate").isFalse();
		}
	}

	private static final class TestAccess implements CapabilityDiagnostic.Access
	{
		private final JsonMapper json;
		private final ObjectNode run;
		private final ObjectNode counts;
		private boolean infrastructureFailure;

		/**
		 * Creates test-owned diagnostic records through the enclosing test scope mapper.
		 *
		 * @param json the test scope's JSON mapper
		 */
		private TestAccess(JsonMapper json)
		{
			this.json = json;
			run = json.createObjectNode();
			counts = json.createObjectNode();
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
		public void prepareOwnedWorkspace(Path runPath, Path workspaceRoot)
		{
		}

		@Override
		public ObjectNode runOwned(Path runPath, Path workspaceRoot, String phase, CapabilityDiagnostic.Operation operation)
			throws IOException
		{
			return operation.run();
		}

		@Override
		public ObjectNode readRun(Path runPath)
		{
			return run;
		}

		@Override
		public void writeRun(Path runPath, ObjectNode replacement)
		{
		}

		@Override
		public void withRunLock(Path runPath, CapabilityDiagnostic.Operation operation) throws IOException
		{
			operation.run();
		}

		@Override
		public String text(ObjectNode object, String field, Path source)
		{
			return object.path(field).asString();
		}

		@Override
		public Path path(String value)
		{
			return Path.of(value);
		}

		@Override
		public ObjectNode object(JsonNode value, String description)
		{
			return (ObjectNode) value;
		}

		@Override
		public ArrayNode array(JsonNode value, String description, Path source)
		{
			return (ArrayNode) value;
		}

		@Override
		public ObjectNode object()
		{
			return json.createObjectNode();
		}

		@Override
		public List<Integer> claimTrials(Path runPath, int count)
		{
			return List.of(1, 2, 3);
		}

		@Override
		public void executeAndRecord(Path runPath, Path suitePath, ObjectNode testCase, List<Integer> trials,
			Path workspaceRoot, Path planPath, int maximumConcurrency, HarnessEvaluationRunner runner)
		{
		}

		@Override
		public ObjectNode outcomeCounts(ObjectNode run, Path runPath)
		{
			return counts;
		}

		@Override
		public boolean hasInfrastructureFailure(Path runPath)
		{
			return infrastructureFailure;
		}
	}
}
