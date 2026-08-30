package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.cat.common.evaluation.AttemptStatus.FAILED;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import io.github.cowwoc.cat.common.secrets.EvaluatorSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the model-free lifecycle diagnostic for one packaged runner.
 */
public final class LifecycleSoak
{
	/**
	 * Supplies the evaluator lifecycle primitives used by model-free lifecycle scenarios.
	 */
	public interface Access
	{
		/**
		 * Creates one invocation-owned lifecycle directory below the CAT work temporary root.
		 *
		 * @param temporaryRoot the CAT work temporary root
		 * @param operation the operation name used in the directory identity
		 * @return the newly created invocation directory
		 * @throws IOException if the directory cannot be created and marked as invocation-owned
		 */
		Path createInvocationDirectory(Path temporaryRoot, String operation) throws IOException;

		/**
		 * Deletes one invocation-owned lifecycle directory.
		 *
		 * @param temporaryRoot the CAT work temporary root that owns the directory
		 * @param directory the invocation directory to delete
		 * @throws IOException if the directory is not owned or cannot be deleted
		 */
		void deleteInvocationDirectory(Path temporaryRoot, Path directory) throws IOException;

		/**
		 * Initializes the retained record for one scripted scenario.
		 *
		 * @param suite the immutable scenario suite
		 * @param prompt the prompt artifact under evaluation
		 * @param harness the scripted harness name
		 * @param profile the scripted execution profile
		 * @param context the immutable validator context
		 * @param runPath the persistent scenario record
		 * @throws IOException if record initialization rejects the scenario inputs
		 */
		void begin(Path suite, Path prompt, String harness, String profile, Path context, Path runPath)
			throws IOException;

		/**
		 * Claims the next whole-suite trials for one scripted record.
		 *
		 * @param runPath the persistent scenario record
		 * @param count the positive number of trials to claim
		 * @return the claimed trial numbers
		 * @throws IOException if the record cannot claim the requested trials
		 */
		List<Integer> claimTrials(Path runPath, int count) throws IOException;

		/**
		 * Prepares an empty workspace for a named scripted operation.
		 *
		 * @param workspace the workspace that must contain no unrelated data
		 * @param operation the operation used in any rejection diagnostic
		 * @throws IOException if the workspace is unsafe or occupied
		 */
		void prepareEmptyWorkspace(Path workspace, String operation) throws IOException;

		/**
		 * Marks a workspace as owned by one persistent evaluation record.
		 *
		 * @param runPath the record that owns the workspace
		 * @param workspace the initially empty or prepared evaluator workspace
		 * @throws IOException if the workspace belongs to another record or has unrelated contents
		 */
		void prepareOwnedWorkspace(Path runPath, Path workspace) throws IOException;

		/**
		 * Reads one retained record as an object value.
		 *
		 * @param source the retained record location
		 * @param description the value description for diagnostics
		 * @return the parsed record
		 * @throws IOException if the record is unavailable or not an object
		 */
		ObjectNode readObject(Path source, String description) throws IOException;

		/**
		 * Atomically writes one retained record.
		 *
		 * @param destination the record destination
		 * @param record the complete record contents
		 * @throws IOException if the record cannot be written
		 */
		void write(Path destination, ObjectNode record) throws IOException;

		/**
		 * Retains one scripted case result in an owned evaluation record.
		 *
		 * @param runPath the persistent scenario record
		 * @param caseId the stable scenario identifier
		 * @param trial the claimed trial number
		 * @param status the terminal scripted assertion status
		 * @param rawResult the retained executor result
		 * @param validatorResult the retained validator result
		 * @throws IOException if the result cannot be recorded
		 */
		void record(Path runPath, String caseId, int trial, AttemptStatus status, Path rawResult,
			Path validatorResult) throws IOException;

		/**
		 * Computes and persists the terminal decision for one scripted record.
		 *
		 * @param runPath the persistent scenario record
		 * @return the terminal decision
		 * @throws IOException if the retained record cannot be decided
		 */
		ObjectNode decide(Path runPath) throws IOException;

		/**
		 * Recovers a stopped scripted evaluation whose workspace is owned by its record.
		 *
		 * @param runPath the persistent scenario record
		 * @param workspace the workspace owned by that record
		 * @return the resulting terminal decision
		 * @throws IOException if recovery cannot safely terminalize the record
		 */
		ObjectNode recover(Path runPath, Path workspace) throws IOException;

		/**
		 * Hands off terminal evidence to durable storage and rewrites retained record paths.
		 *
		 * @param runPath the persistent scenario record
		 * @param workspace the workspace that owns the terminal evidence
		 * @param destination the initially absent durable evidence directory
		 * @param temporaryRoot the CAT work temporary root
		 * @param workRoot the CAT work root
		 * @return the completed handoff receipt
		 * @throws IOException if evidence cannot be handed off safely
		 */
		ObjectNode handoff(Path runPath, Path workspace, Path destination, Path temporaryRoot, Path workRoot)
			throws IOException;

		/**
		 * Resolves the durable CAT work root above an invocation-local temporary root.
		 *
		 * @param temporaryRoot the CAT work temporary root
		 * @return the durable CAT work root
		 * @throws IOException if the temporary root has no durable work parent
		 */
		Path workRoot(Path temporaryRoot) throws IOException;

		/**
		 * Atomically publishes a lifecycle receipt.
		 *
		 * @param destination the receipt destination
		 * @param receipt the completed receipt
		 * @throws IOException if publication fails
		 */
		void writeAtomically(Path destination, ObjectNode receipt) throws IOException;
	}

	private static volatile Access access;

	/**
	 * Prevents construction of lifecycle operations.
	 */
	private LifecycleSoak()
	{
	}

	/**
	 * Registers evaluator lifecycle primitives used by model-free scenarios.
	 *
	 * @param access the coordinator-owned lifecycle operations
	 */
	public static void configure(Access access)
	{
		LifecycleSoak.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Runs all model-free lifecycle scenarios and retains their receipt for one packaged runner fingerprint.
	 *
	 * @param temporaryRoot the CAT work temporary directory that owns this invocation's scenario data
	 * @param packagedRunnerFingerprint the fingerprint of the packaged runner the receipt authorizes
	 * @return the completed lifecycle receipt, including its retained path
	 * @throws IOException if a scenario or receipt cannot reach its required terminal outcome
	 */
	public static ObjectNode run(Path temporaryRoot, String packagedRunnerFingerprint) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return run(scope.json(), temporaryRoot, packagedRunnerFingerprint);
		}
	}

	/**
	 * Runs lifecycle scenarios with a mapper owned by the enclosing lifecycle operation.
	 *
	 * @param json the mapper that creates lifecycle receipts and outcomes
	 * @param temporaryRoot the CAT work temporary directory that owns scenario data
	 * @param packagedRunnerFingerprint the fingerprint of the packaged runner
	 * @return the completed lifecycle receipt
	 * @throws IOException if a scenario or receipt cannot reach its terminal outcome
	 */
	private static ObjectNode run(JsonMapper json,
		Path temporaryRoot, String packagedRunnerFingerprint)
		throws IOException
	{
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(packagedRunnerFingerprint, "packagedRunnerFingerprint").isNotBlank();
		Path normalizedTemporaryRoot = temporaryRoot.toAbsolutePath().normalize();
		Path workRoot = access().workRoot(normalizedTemporaryRoot);
		Path directory = workRoot.resolve(WorkDirectories.LIFECYCLE_SOAKS);
		Path receiptPath = directory.resolve(packagedRunnerFingerprint + ".json");
		if (Files.isRegularFile(receiptPath, NOFOLLOW_LINKS))
		{
			ObjectNode existing = access().readObject(receiptPath, "lifecycle soak receipt");
			LifecycleSoakReceipt.verify(json, existing, receiptPath, packagedRunnerFingerprint);
			return existing.deepCopy();
		}

		Files.createDirectories(directory);
		Path fixtureRoot = access().createInvocationDirectory(normalizedTemporaryRoot,
			"prompt-evaluation-lifecycle-fixture");
		try
		{
			LifecycleSoakFixture fixture = createLifecycleSoakFixture(fixtureRoot);
			ObjectNode receipt = LifecycleSoakReceipt.passed(json, packagedRunnerFingerprint);
			ArrayNode outcomes = receipt.withArray("outcomes");
			outcomes.removeAll();
			for (int suite = 1; suite <= LifecycleSoakReceipt.suiteCount(); ++suite)
				outcomes.add(executeScriptedLifecycleScenario(json, normalizedTemporaryRoot, fixture, suite));
			LifecycleSoakReceipt.verify(json, receipt, receiptPath, packagedRunnerFingerprint);
			access().writeAtomically(receiptPath, receipt);
			ObjectNode result = receipt.deepCopy();
			result.put("receipt", receiptPath.toString());
			return result;
		}
		finally
		{
			access().deleteInvocationDirectory(normalizedTemporaryRoot, fixtureRoot);
		}
	}

	/**
	 * Verifies one lifecycle receipt identified by its exact packaged runner.
	 *
	 * @param temporaryRoot the CAT work temp directory that owns lifecycle-check receipts
	 * @param packagedRunnerFingerprint the verified packaged runner
	 * @throws IOException if no matching successful check exists
	 */
	public static void verify(Path temporaryRoot, String packagedRunnerFingerprint) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			verify(scope.json(), temporaryRoot, packagedRunnerFingerprint);
		}
	}

	/**
	 * Verifies one lifecycle receipt with a mapper owned by the enclosing verification operation.
	 *
	 * @param json the mapper that verifies the lifecycle receipt
	 * @param temporaryRoot the CAT work temporary directory that owns lifecycle receipts
	 * @param packagedRunnerFingerprint the fingerprint of the packaged runner
	 * @throws IOException if no matching successful receipt exists
	 */
	private static void verify(JsonMapper json, Path temporaryRoot, String packagedRunnerFingerprint)
		throws IOException
	{
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(packagedRunnerFingerprint, "packagedRunnerFingerprint").isNotBlank();
		Path receiptPath = access().workRoot(temporaryRoot).resolve(
			WorkDirectories.LIFECYCLE_SOAKS).
			resolve(packagedRunnerFingerprint + ".json");
		if (!Files.isRegularFile(receiptPath, NOFOLLOW_LINKS))
		{
			throw new IOException("Formal evaluation requires a passing lifecycle check for this packaged runner. " +
				"Run prompt-evaluation lifecycle-soak with the selected build plan and runner before certification: " +
				receiptPath);
		}
		LifecycleSoakReceipt.verify(json, access().readObject(receiptPath, "lifecycle soak receipt"),
			receiptPath, packagedRunnerFingerprint);
	}

	/**
	 * Produces one deterministic lifecycle result without starting a model or native harness process.
	 *
	 * @param temporaryRoot the CAT work temp directory that owns this scenario
	 * @param json the mapper that creates lifecycle receipts and outcomes
	 * @param fixture the immutable scenario inputs
	 * @param suite the one-based synthetic suite number
	 * @return the retained terminal outcome for that suite
	 * @throws IOException if scenario setup, execution, or cleanup fails
	 */
	private static ObjectNode executeScriptedLifecycleScenario(JsonMapper json,
		Path temporaryRoot, LifecycleSoakFixture fixture,
		int suite) throws IOException
	{
		Path scenarioRoot = access().createInvocationDirectory(temporaryRoot, "prompt-evaluation-lifecycle-" + suite);
		try
		{
			return switch ((suite - 1) % 6)
			{
				case 0 -> completeScriptedLifecycleScenario(json, fixture, scenarioRoot, suite, null);
				case 1 -> workspaceCollisionScenario(json, fixture, scenarioRoot, suite);
				case 2 -> missingCheckReceiptScenario(json, fixture, scenarioRoot, suite);
				case 3 -> ownerLossScenario(json, fixture, scenarioRoot, suite);
				case 4 -> staleRuntimeScenario(json, fixture, scenarioRoot, suite);
				case 5 -> handoffAndCleanupScenario(json, temporaryRoot, fixture, scenarioRoot, suite);
				default -> throw new AssertionError("Unexpected lifecycle script branch");
			};
		}
		finally
		{
			if (Files.exists(scenarioRoot, NOFOLLOW_LINKS))
				access().deleteInvocationDirectory(temporaryRoot, scenarioRoot);
		}
	}

	/**
	 * Creates the immutable inputs shared by the model-free lifecycle scenarios.
	 *
	 * @param fixtureRoot the invocation-owned fixture directory
	 * @return the validated suite, prompt, and context inputs
	 * @throws IOException if fixture setup fails
	 */
	private static LifecycleSoakFixture createLifecycleSoakFixture(Path fixtureRoot) throws IOException
	{
		Path suite = fixtureRoot.resolve("suite");
		Files.createDirectories(suite.resolve("requested-change"));
		Files.createDirectories(suite.resolve("unrelated-request"));
		Files.createDirectories(suite.resolve("edge-condition"));
		Files.writeString(suite.resolve("requested-change/scenario.md"), """
			## Scenario

			Complete the requested task.

			## Assertions

			### result

			The requested result is retained.
			""", StandardCharsets.UTF_8);
		Files.writeString(suite.resolve("unrelated-request/scenario.md"), """
			## Scenario

			Complete unrelated work.

			## Assertions

			### scope

			The retained result does not invoke the target.
			""", StandardCharsets.UTF_8);
		Files.writeString(suite.resolve("edge-condition/scenario.md"), """
			## Scenario

			Complete the edge condition.

			## Assertions

			### edge-result

			The edge-condition result is retained.
			""", StandardCharsets.UTF_8);
		Files.createDirectories(suite.resolve("requested-change/project"));
		Files.createDirectories(suite.resolve("unrelated-request/project"));
		Files.createDirectories(suite.resolve("edge-condition/project"));
		Files.writeString(suite.resolve("requested-change/project/project.txt"), "requested fixture\n",
			StandardCharsets.UTF_8);
		Files.writeString(suite.resolve("unrelated-request/project/project.txt"), "unrelated fixture\n",
			StandardCharsets.UTF_8);
		Files.writeString(suite.resolve("edge-condition/project/project.txt"), "edge fixture\n",
			StandardCharsets.UTF_8);
		Files.createDirectories(suite);
		Path manifest = suite.resolve("suite.json");
		Files.writeString(manifest, """
			{
			  "schema_version": 3,
			  "purpose": "certification",
			  "routing_owner": "common/default-rules.md",
			  "sprt": {
			    "accept_pass_rate": 0.95,
			    "reject_pass_rate": 0.85,
			    "false_accept_rate": 0.05,
			    "false_reject_rate": 0.05
			  },
			  "scenarios": {
			    "requested-change":{"measures":["organic_selection","application"],
			     "route_expectation":{"mode":"organic","selected":["common/rule.md"]}},
			    "unrelated-request":{"measures":["organic_selection","application"],
			     "route_expectation":{"mode":"organic","not_selected":["common/rule.md"]}},
			    "edge-condition":{"measures":["organic_selection","application"],
			     "route_expectation":{"mode":"organic","selected":["common/rule.md"]}}
			  }
			}
			""", StandardCharsets.UTF_8);
		SuiteContract.validate(suite);
		Path prompt = fixtureRoot.resolve("prompt.md");
		Path context = fixtureRoot.resolve("context.md");
		Files.writeString(prompt, "Lifecycle fixture prompt.\n", StandardCharsets.UTF_8);
		Files.writeString(context, "Lifecycle fixture context.\n", StandardCharsets.UTF_8);
		return new LifecycleSoakFixture(suite, prompt, context);
	}

	/**
	 * Runs one normal in-process harness cycle through claim, execution evidence, validation evidence, and a terminal
	 * decision.
	 *
	 * @param json the mapper that creates lifecycle receipts and outcomes
	 * @param fixture the shared immutable scenario inputs
	 * @param scenarioRoot the invocation-owned scenario directory
	 * @param suite the one-based synthetic suite number
	 * @param fault the optional injected fault name
	 * @return the terminal scenario outcome
	 * @throws IOException if any lifecycle boundary rejects its input
	 */
	private static ObjectNode completeScriptedLifecycleScenario(JsonMapper json,
		LifecycleSoakFixture fixture, Path scenarioRoot,
		int suite, String fault) throws IOException
	{
		Path run = scenarioRoot.resolve("run.json");
			access().begin(fixture.suite(), fixture.prompt(), "scripted", "model-free", fixture.context(), run);
			List<Integer> claims = access().claimTrials(run, 1);
		if (!claims.equals(List.of(1)))
			throw new IOException("Lifecycle script did not claim its first trial: " + scenarioRoot);
		Path workspace = scenarioRoot.resolve("workspace");
			access().prepareOwnedWorkspace(run, workspace);
			ObjectNode record = access().readObject(run, "lifecycle scenario run");
		record.put("workspace", workspace.toString());
			access().write(run, record);
		for (String caseId : List.of("requested-change", "unrelated-request", "edge-condition"))
		{
			Path raw = workspace.resolve("trial-1/" + caseId + "-raw.txt");
			Path validator = workspace.resolve("trial-1/" + caseId + "-validator.txt");
			Files.createDirectories(raw.getParent());
			Files.writeString(raw, "scripted executor result\n", StandardCharsets.UTF_8);
			Files.writeString(validator, "scripted validator result\n", StandardCharsets.UTF_8);
				access().record(run, caseId, 1, FAILED, raw, validator);
		}
			ObjectNode decision = access().decide(run);
		if (!decision.path("overall_decision").asString().equals("FAIL"))
			throw new IOException("Lifecycle script did not reach its expected terminal decision: " + scenarioRoot);
			ObjectNode result = json.createObjectNode();
		result.put("suite", suite);
		ArrayNode stages = result.putArray("stages");
		stages.add("claim");
		stages.add("execution");
		stages.add("validation");
		if (fault != null)
			result.put("fault", fault);
		result.put("terminal_state", "completed");
		return result;
	}

	/**
	 * Exercises rejection of a collision before a scripted executor can retain a result.
	 *
	 * @param json the mapper that creates lifecycle receipts and outcomes
	 * @param fixture the immutable scenario inputs
	 * @param scenarioRoot the invocation-owned scenario directory
	 * @param suite the one-based synthetic suite number
	 * @return the terminal collision outcome
	 * @throws IOException if collision handling or the later normal cycle fails
	 */
	private static ObjectNode workspaceCollisionScenario(JsonMapper json,
		LifecycleSoakFixture fixture, Path scenarioRoot, int suite)
		throws IOException
	{
		Path collision = scenarioRoot.resolve("collision");
		Files.createDirectories(collision);
		Files.writeString(collision.resolve("unowned.txt"), "collision\n", StandardCharsets.UTF_8);
		try
		{
				access().prepareEmptyWorkspace(collision, "Lifecycle script");
			throw new IOException("Lifecycle script accepted an occupied workspace: " + collision);
		}
		catch (IOException expected)
		{
			if (!expected.getMessage().contains("not empty"))
				throw expected;
		}
		ObjectNode result = completeScriptedLifecycleScenario(json, fixture, scenarioRoot, suite, "workspace_collision");
		result.put("terminal_state", "rejected_before_execution");
		return result;
	}

	/**
	 * Exercises the promotion guard when a candidate has no retained regression-check evidence.
	 *
	 * @param json the mapper that creates lifecycle receipts and outcomes
	 * @param fixture the immutable scenario inputs
	 * @param scenarioRoot the invocation-owned scenario directory
	 * @param suite the one-based synthetic suite number
	 * @return the terminal missing-check outcome
	 * @throws IOException if the promotion guard or the later normal cycle fails
	 */
	private static ObjectNode missingCheckReceiptScenario(JsonMapper json,
		LifecycleSoakFixture fixture, Path scenarioRoot, int suite)
		throws IOException
	{
		Path candidateState = scenarioRoot.resolve("candidate-state.json");
		Files.writeString(candidateState, "{\"current_promotion\":{}}", StandardCharsets.UTF_8);
		try
		{
			CandidateState.verifyPromotionReceipt(candidateState);
			throw new IOException("Lifecycle script accepted a promotion without its regression check: " + candidateState);
		}
		catch (IOException expected)
		{
			if (!expected.getMessage().contains("no regression-check receipt"))
				throw expected;
		}
		ObjectNode result = completeScriptedLifecycleScenario(json, fixture, scenarioRoot, suite, "missing_check_receipt");
		result.put("terminal_state", "rejected_before_promotion");
		return result;
	}

	/**
	 * Exercises terminal recovery after an evaluator loses ownership before retaining any worker result.
	 *
	 * @param json the mapper that creates lifecycle receipts and outcomes
	 * @param fixture the immutable scenario inputs
	 * @param scenarioRoot the invocation-owned scenario directory
	 * @param suite the one-based synthetic suite number
	 * @return the terminal recovery outcome
	 * @throws IOException if recovery does not terminalize the abandoned run
	 */
	private static ObjectNode ownerLossScenario(JsonMapper json,
		LifecycleSoakFixture fixture, Path scenarioRoot, int suite)
		throws IOException
	{
		Path run = scenarioRoot.resolve("owner-loss-run.json");
			access().begin(fixture.suite(), fixture.prompt(), "scripted", "model-free", fixture.context(), run);
		Path workspace = scenarioRoot.resolve("owner-loss-workspace");
			access().prepareOwnedWorkspace(run, workspace);
			ObjectNode record = access().readObject(run, "owner-loss lifecycle run");
		record.put("workspace", workspace.toString());
			access().write(run, record);
			ObjectNode recovered = access().recover(run, workspace);
		if (!recovered.path("overall_decision").asString().equals("INFRASTRUCTURE_FAILED"))
			throw new IOException("Lifecycle script did not terminalize owner loss: " + scenarioRoot);
			ObjectNode result = json.createObjectNode();
		result.put("suite", suite);
		result.put("fault", "owner_loss");
		ArrayNode stages = result.putArray("stages");
		stages.add("claim");
		stages.add("interruption");
		stages.add("recovery");
		result.put("terminal_state", "recovered");
		return result;
	}

	/**
	 * Exercises the stale-runtime guard as a terminal pre-formal rejection.
	 *
	 * @param json the mapper that creates lifecycle receipts and outcomes
	 * @param fixture the immutable scenario inputs
	 * @param scenarioRoot the invocation-owned scenario directory
	 * @param suite the one-based synthetic suite number
	 * @return the terminal stale-runtime outcome
	 * @throws IOException if the scripted lifecycle does not complete
	 */
	private static ObjectNode staleRuntimeScenario(JsonMapper json,
		LifecycleSoakFixture fixture, Path scenarioRoot, int suite)
		throws IOException
	{
		Path checkRoot = scenarioRoot.resolve("stale-runtime-check");
		Path artifactFile = checkRoot.resolve("runner/client/runner.txt");
		Files.createDirectories(artifactFile.getParent());
		Files.writeString(artifactFile, "checked runner\n", StandardCharsets.UTF_8);
		Path artifact = artifactFile.getParent().getParent();
		RunnerRuntimeManifest.write(artifact);
		String checkedFingerprint = RunnerRuntimeManifest.fingerprint(artifact);
		Path receipt = checkRoot.resolve(WorkDirectories.LIFECYCLE_SOAKS).
			resolve(checkedFingerprint + ".json");
		Files.createDirectories(receipt.getParent());
			access().writeAtomically(receipt, LifecycleSoakReceipt.passed(json, checkedFingerprint));
		Files.writeString(artifactFile, "changed runner\n", StandardCharsets.UTF_8);
		Files.delete(artifact.resolve(RunnerRuntimeManifest.PATH));
		RunnerRuntimeManifest.write(artifact);
		try
		{
				RuntimeVerification.verifyLifecycleSoak(checkRoot, artifact);
			throw new IOException("Lifecycle script accepted a stale runner receipt: " + artifact);
		}
		catch (IOException expected)
		{
			if (!expected.getMessage().contains("requires a passing lifecycle check"))
				throw expected;
		}
		ObjectNode result = completeScriptedLifecycleScenario(json, fixture, scenarioRoot, suite, "stale_runtime");
		result.put("terminal_state", "rejected_before_formal_evaluation");
		return result;
	}

	/**
	 * Exercises retained-evidence handoff and deletion after a terminal scripted evaluation.
	 *
	 * @param temporaryRoot the CAT work temp directory that owns the scenario's scratch data
	 * @param json the mapper that creates lifecycle receipts and outcomes
	 * @param fixture the immutable scenario inputs
	 * @param scenarioRoot the invocation-owned scenario directory
	 * @param suite the one-based synthetic suite number
	 * @return the terminal handoff and cleanup outcome
	 * @throws IOException if handoff or cleanup fails
	 */
	private static ObjectNode handoffAndCleanupScenario(JsonMapper json,
		Path temporaryRoot, LifecycleSoakFixture fixture,
		Path scenarioRoot, int suite) throws IOException
	{
		ObjectNode result = completeScriptedLifecycleScenario(json, fixture, scenarioRoot, suite, "retained_path_rewrite");
		Path run = scenarioRoot.resolve("run.json");
		Path workspace = scenarioRoot.resolve("workspace");
		Path workRoot = temporaryRoot.getParent();
		if (workRoot == null)
			throw new IOException("CAT work temp directory has no CAT work root: " + temporaryRoot);
		Path retained = workRoot.resolve("prompt-evaluation-lifecycle-retained-" + suite);
		ObjectNode handoff = access().handoff(run, workspace, retained, temporaryRoot, workRoot);
		if (!Files.isRegularFile(Path.of(handoff.path("run").asString()), NOFOLLOW_LINKS))
			throw new IOException("Lifecycle script did not retain its handed-off run: " + retained);
		TemporaryDirectories.deleteRecursively(retained);
		result.path("stages").asArray().add("handoff");
		result.path("stages").asArray().add("cleanup");
		result.put("terminal_state", "retained_after_path_rewrite");
		return result;
	}

	/**
	 * Holds the immutable inputs used by every model-free lifecycle scenario.
	 *
	 * @param suite the validated synthetic suite
	 * @param prompt the synthetic prompt artifact
	 * @param context the synthetic validator context
	 */
	private record LifecycleSoakFixture(Path suite, Path prompt, Path context)
	{
		/**
		 * Creates one immutable lifecycle scenario input set.
		 *
		 * @param suite the validated synthetic suite
		 * @param prompt the synthetic prompt artifact
		 * @param context the synthetic validator context
		 */
		private LifecycleSoakFixture
		{
			requireThat(suite, "suite").isNotNull();
			requireThat(prompt, "prompt").isNotNull();
			requireThat(context, "context").isNotNull();
		}
	}

	/**
	 * Returns the registered evaluator lifecycle primitives.
	 *
	 * @return the coordinator-owned lifecycle operations
	 */
	private static Access access()
	{
		EvaluatorSecrets.access().initialize();
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure lifecycle-soak access");
		return result;
	}
}
