package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.github.cowwoc.cat.common.ToolScope;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests persisted certification decision composition.
 */
public final class DecisionTest
{
	private static final Statistics.Parameters PARAMETERS =
		new Statistics.Parameters(0.95, 0.95, 0.85, 0.05, 0.05, 100);
	private final ToolScope scope = new ToolScope();
	/**
	 * Creates the test fixture.
	 */
	public DecisionTest()
	{
	}

	/**
	 * Closes the operation scope after this test class finishes.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies a fixed-budget benchmark remains running while preserving its computed decision measurements.
	 */
	@Test
	public void fixedBenchmarkOverridesTerminalDecision()
	{
		Statistics.Parameters parameters = new Statistics.Parameters(0.95, 0.95, 0.85, 0.05, 0.05, 100);
		Decision.Input input = new Decision.Input(parameters, List.of(new Decision.Outcome(true)), false, false, true);

		ObjectNode result = Decision.evaluate(scope.json(), input);

		requireThat(result.path("overall_decision").asString(), "overallDecision").isEqualTo("RUNNING");
		requireThat(result.path("complete_suite_runs").asInt(), "completeSuiteRuns").isEqualTo(1);
		requireThat(result.path("complete_suite_passes").asInt(), "completeSuitePasses").isEqualTo(1);
		requireThat(result.path("required_compliance").asDouble(), "requiredCompliance").isEqualTo(0.95);
	}

	/**
	 * Verifies a retained terminal decision is reusable only after fixed benchmark work is complete.
	 */
	@Test
	public void reusesOnlyCompletedTerminalDecision()
	{
		ObjectNode decision = scope.json().createObjectNode().put("overall_decision", "PASS");

		requireThat(Decision.isReusable(decision, false), "reusable").isTrue();
		requireThat(Decision.isReusable(decision, true), "benchmarkInProgress").isFalse();
	}

	/**
	 * Verifies a reusable decision validates evidence without recalculating or writing the record.
	 *
	 * @throws IOException if the direct lifecycle fails
	 */
	@Test
	public void reusesValidatedTerminalRecord() throws IOException
	{
		TestAccess access = new TestAccess(scope.json());
		access.run.putObject("decision").put("overall_decision", "PASS").put("complete_suite_runs", 1).
			put("infrastructure_failure", false);

		ObjectNode result = Decision.update(scope.json(), Path.of("run.json"), true, access);

		requireThat(result.path("overall_decision").asString(), "decision").isEqualTo("PASS");
		requireThat(access.evidenceValidated, "evidenceValidated").isTrue();
		requireThat(access.written, "written").isFalse();
	}

	/**
	 * Verifies an input change prevents a new decision projection.
	 *
	 * @throws IOException if the expected refusal cannot be evaluated
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*inputs changed.*")
	public void rejectsStaleInputsBeforeProjection() throws IOException
	{
		TestAccess access = new TestAccess(scope.json());
		access.inputsValid = false;

		Decision.update(scope.json(), Path.of("run.json"), true, access);
	}

	private static final class TestAccess implements Decision.Access
	{
		private final ObjectNode run;

		/**
		 * Creates an access fixture backed by the caller's mapper.
		 *
		 * @param json the test operation's JSON mapper
		 */
		private TestAccess(JsonMapper json)
		{
			run = json.createObjectNode();
		}

		private boolean inputsValid = true;
		private boolean evidenceValidated;
		private boolean written;

		@Override
		public ObjectNode withLock(Path runPath, Decision.LockedOperation operation) throws IOException
		{
			return operation.run();
		}

		@Override
		public ObjectNode read(Path runPath)
		{
			return run;
		}

		@Override
		public void write(Path runPath, ObjectNode replacement)
		{
			written = true;
		}

		@Override
		public void validateInputs(ObjectNode current, Path runPath) throws IOException
		{
			if (!inputsValid)
				throw new IOException("inputs changed");
		}

		@Override
		public void validateEvidence(ObjectNode current, Path runPath)
		{
			evidenceValidated = true;
		}

		@Override
		public Decision.Input input(ObjectNode current, Path runPath)
		{
			return new Decision.Input(PARAMETERS, List.of(), false, false, false);
		}

		@Override
		public boolean interrupted(Path runPath)
		{
			return false;
		}

		@Override
		public boolean fixedBenchmarkInProgress(ObjectNode current, int completeTrials, boolean infrastructureFailure)
		{
			return false;
		}
	}
}
