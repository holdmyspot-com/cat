package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests read-only candidate-status interpretation.
 */
public final class GoalStatusTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public GoalStatusTest()
	{
	}

	/**
	 * Closes the test-owned tool scope after every status test.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies a changed source makes a completed regression gate stale.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsChangedSourceAsStaleGate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("goal-status"));
			Path prompt = Files.writeString(root.resolve("prompt.md"), "current");
			Path check = Files.writeString(root.resolve("check.json"), """
				{"regression_check":{"status":"passed"},"prompt_source":"%s","prompt_sha256":"retained"}
				""".formatted(prompt));
			Path state = Files.writeString(root.resolve("state.json"), "{" +
				"\"latest_check\":\"" + check + "\"}");

			ObjectNode result = GoalStatus.status(scope.json(), state, null, new Access(scope.json()));

			requireThat(result.path("gate_status").asString(), "gateStatus").isEqualTo("stale");
			requireThat(result.path("stale_reason").asString(), "staleReason").contains("Prompt artifact changed");
		}
	}

	/**
	 * Verifies the newest available checkpoint selects the next stage while older missing history remains visible.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsUnavailableOlderHistory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("goal-status"));
			Path current = Files.writeString(root.resolve("current.json"), "{}");
			Path missing = root.resolve("missing.json");
			Path state = Files.writeString(root.resolve("state.json"), """
				{"formal_failures":[{"run":"%s","observed_at_epoch_millis":2}],
				"recurrence_reviews":[{"status":"completed","result":"%s","completed_at_epoch_millis":1}]}
				""".formatted(current, missing));

			ObjectNode result = GoalStatus.status(scope.json(), state, null, new Access(scope.json()));

			requireThat(result.path("current_record").asString(), "currentRecord").isEqualTo(current.toString());
			requireThat(result.path("unavailable_historical_records").get(0).path("record").asString(), "history").
				isEqualTo(missing.toString());
		}
	}

	/**
	 * Verifies conflicting handwritten checkpoint prose is rejected.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*goal-status.*")
	public void rejectsConflictingGoalCheckpoint() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("goal-status"));
			Path current = Files.writeString(root.resolve("current.json"), "{}");
			Path state = Files.writeString(root.resolve("state.json"), """
				{"formal_failures":[{"run":"%s","observed_at_epoch_millis":1}]}
				""".formatted(current));
			Path goal = Files.writeString(root.resolve("goal.md"), """
				#### Current tuning checkpoint
				Current retained run: another-record.json
				""");

			GoalStatus.status(scope.json(), state, goal, new Access(scope.json()));
		}
	}

	/**
	 * Supplies test-owned immutable evidence access.
	 */
	private static final class Access implements GoalStatus.Access
	{
		private final JsonMapper json;

		/**
		 * Creates test-owned immutable-evidence access.
		 *
		 * @param json the test scope's JSON mapper
		 */
		private Access(JsonMapper json)
		{
			this.json = json;
		}

		/**
		 * Reads one test-owned JSON record.
		 *
		 * @param path the record path
		 * @param description the record's diagnostic description
		 * @return the parsed test-owned record
		 * @throws IOException if the record is not an object
		 */
		@Override
		public ObjectNode read(Path path, String description) throws IOException
		{
			JsonNode value = json.readTree(Files.readString(path));
			if (value instanceof ObjectNode result)
				return result;
			throw new IOException("Expected " + description + ": " + path);
		}

		/**
		 * Accepts the test-owned immutable evaluation inputs.
		 *
		 * @param run the completed evaluation record
		 * @param runPath the retained evaluation record path
		 */
		@Override
		public void validateRunInputs(ObjectNode run, Path runPath)
		{
		}

		/**
		 * Indicates whether the test record has a regression-check result.
		 *
		 * @param run the retained evaluation record
		 * @return whether the record has a regression-check result
		 */
		@Override
		public boolean hasCompletedRegressionCheck(ObjectNode run)
		{
			return run.path("regression_check").isObject();
		}

		/**
		 * Returns a changed fingerprint for every test source file.
		 *
		 * @param artifact the source file
		 * @return a fingerprint different from the retained value
		 */
		@Override
		public String sha256(Path artifact)
		{
			return "changed";
		}

		/**
		 * Returns a stable test-suite fingerprint.
		 *
		 * @param suitePath the scenario-suite root
		 * @return the test-suite fingerprint
		 */
		@Override
		public String suiteFingerprint(Path suitePath)
		{
			return "suite";
		}
	}
}
