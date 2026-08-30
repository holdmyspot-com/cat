package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.PromptEvaluation;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.TemporaryDirectories;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates one evaluator record, workspace, and retained input set before execution begins.
 */
public final class Preparation
{
	private static final SuiteSnapshot.Access SUITE_SNAPSHOTS = new SuiteSnapshot.Access()
	{
		@Override
		public void copyFixture(Path source, Path destination) throws IOException
		{
			EvaluationFiles.copyFixture(source, destination);
		}

		@Override
		public void delete(Path root) throws IOException
		{
			TemporaryDirectories.deleteRecursively(root);
		}
	};

	/**
	 * Prevents construction of a utility class.
	 */
	private Preparation()
	{
	}

	/**
	 * Initializes an immutable evaluation record for a caller that supplies its destination.
	 *
	 * @param suitePath the testcase suite
	 * @param promptArtifact the prompt artifact under evaluation
	 * @param harness the executing harness
	 * @param profile the fixed execution profile
	 * @param executionContext the versioned validator context
	 * @param runPath the persistent result record
	 * @throws IOException if the inputs are invalid or the record cannot be created
	 */
	public static void begin(Path suitePath, Path promptArtifact, String harness, String profile, Path executionContext,
		Path runPath) throws IOException
	{
		Path parent = runPath.toAbsolutePath().normalize().getParent();
		if (parent == null)
			throw new IOException("Output has no parent directory: " + runPath);
		try (ToolScope scope = new ToolScope())
		{
			initialize(scope.json(), suitePath, promptArtifact, harness, profile, executionContext, runPath, parent);
		}
	}

	/**
	 * Initializes a run record whose repeated input files are retained under one shared immutable-artifact root.
	 *
	 * @param json the preparation operation's JSON mapper
	 * @param suitePath the testcase suite
	 * @param promptArtifact the prompt artifact under evaluation
	 * @param harness the executing harness
	 * @param profile the fixed execution profile
	 * @param executionContext the versioned validator context
	 * @param runPath the persistent result record
	 * @param immutableArtifactRoot the shared work root for reusable input copies
	 * @throws IOException if an input is invalid or the record cannot be created
	 */
	public static void initialize(JsonMapper json, Path suitePath, Path promptArtifact, String harness, String profile,
		Path executionContext, Path runPath, Path immutableArtifactRoot) throws IOException
	{
		Path finalSuitePath = suitePath.toAbsolutePath().normalize();
		Path finalPromptArtifact = promptArtifact.toAbsolutePath().normalize();
		Path finalExecutionContext = executionContext.toAbsolutePath().normalize();
		Path finalRunPath = runPath.toAbsolutePath().normalize();
		Path finalImmutableArtifactRoot = immutableArtifactRoot.toAbsolutePath().normalize();
		PromptEvaluation.withRunLock(finalRunPath, () ->
		{
			SuiteContract.validate(finalSuitePath);
			SuitePurpose purpose = SuitePurpose.parse(PromptEvaluation.text(
				SuiteContract.read(finalSuitePath), "purpose", finalSuitePath));
			if (Files.exists(finalRunPath))
				throw new IOException("Evaluation record already exists: " + finalRunPath);
			if (harness.isBlank() || profile.isBlank())
				throw new IOException("Harness and profile must be non-blank");
			if (!Files.isRegularFile(finalPromptArtifact))
				throw new IOException("Expected prompt artifact file: " + finalPromptArtifact);
			if (!Files.isRegularFile(finalExecutionContext))
				throw new IOException("Expected execution-context file: " + finalExecutionContext);

			Path suiteSnapshot = SuiteSnapshot.copy(SUITE_SNAPSHOTS, finalSuitePath, finalRunPath);
			Path retainedSuite = SuiteStore.retain(finalImmutableArtifactRoot, suiteSnapshot);
			Path snapshotDirectory = suiteSnapshot;
			if (Files.isRegularFile(suiteSnapshot))
				snapshotDirectory = suiteSnapshot.getParent();
			TemporaryDirectories.deleteRecursively(snapshotDirectory);
			Path promptSnapshot = FileStore.retain(finalImmutableArtifactRoot, finalPromptArtifact);
			Path contextSnapshot = FileStore.retain(finalImmutableArtifactRoot,
				finalExecutionContext);
			Path attemptLedger = finalRunPath.resolveSibling(finalRunPath.getFileName() +
				PromptEvaluation.ATTEMPT_LEDGER_SUFFIX);
			try
			{
				Files.createFile(attemptLedger);
				ObjectNode run = json.createObjectNode();
				run.put("schema_version", PromptEvaluation.SCHEMA_VERSION);
				run.put("purpose", purpose.name().toLowerCase(java.util.Locale.ROOT));
				run.put("suite_source", finalSuitePath.toString());
				run.put("suite", retainedSuite.toString());
				run.put("suite_sha256", PromptEvaluation.suiteFingerprint(retainedSuite));
				run.put("prompt_source", finalPromptArtifact.toString());
				run.put("prompt_artifact", promptSnapshot.toString());
				run.put("prompt_sha256", PromptEvaluation.sha256(promptSnapshot));
				run.put("harness", harness);
				run.put("profile", profile);
				run.put("execution_context_source", finalExecutionContext.toString());
				run.put("execution_context", contextSnapshot.toString());
				run.put("execution_context_sha256", PromptEvaluation.sha256(contextSnapshot));
				run.put("attempt_ledger", attemptLedger.toString());
				run.putObject("attempt_index");
				run.put("next_trial", 1);
				PromptEvaluation.write(finalRunPath, run);
			}
			catch (IOException e)
			{
				Files.deleteIfExists(attemptLedger);
				throw e;
			}
		});
	}

	/**
	 * Creates a record from an artifact supplied directly by a focused evaluator test.
	 *
	 * @param json the preparation operation's JSON mapper
	 * @param temporaryRoot the CAT work temporary directory that owns the record
	 * @param suitePath the version-controlled test suite
	 * @param promptPath the prompt artifact under test
	 * @param harness the selected harness identifier
	 * @param contextPath the version-controlled independent-validator context
	 * @param runner the selected harness runner launcher
	 * @param runnerArtifact the rebuilt harness artifact that the runner uses
	 * @param retainedDirectory the initially absent evidence directory
	 * @return the workspace, plan, and run record that form one ready evaluation
	 * @throws IOException if a prerequisite cannot be verified or an owned artifact cannot be created
	 */
	public static ObjectNode direct(JsonMapper json, Path temporaryRoot, Path suitePath, Path promptPath,
		String harness, Path contextPath, Path runner, Path runnerArtifact, Path retainedDirectory) throws IOException
	{
		PromptEvaluation.validateEvaluationInputs(suitePath, promptPath, harness, contextPath, runner);
		Profile profile = ExecutionPlan.resolveRunnerProfile(runner, temporaryRoot);
		return create(json, temporaryRoot, suitePath, promptPath, harness, contextPath, runner,
			ExecutionPlan.directAssemblyIdentity(json, runnerArtifact), retainedDirectory, profile, profile,
			null);
	}

	/**
	 * Creates an ordinary evaluation record from a verified build assembly.
	 *
	 * @param json the preparation operation's JSON mapper
	 * @param temporaryRoot the CAT work temporary directory that owns the record
	 * @param suitePath the version-controlled test suite
	 * @param promptPath the prompt artifact under test
	 * @param harness the selected harness identifier
	 * @param contextPath the independent-validator context
	 * @param runner the selected harness runner launcher
	 * @param assembly the verified build inputs and runner artifact
	 * @param retainedDirectory the initially absent evidence directory
	 * @return the workspace, plan, and run record that form one ready evaluation
	 * @throws IOException if a prerequisite cannot be verified or an owned artifact cannot be created
	 */
	public static ObjectNode assembled(JsonMapper json, Path temporaryRoot, Path suitePath, Path promptPath,
		String harness,
		Path contextPath, Path runner, ObjectNode assembly, Path retainedDirectory) throws IOException
	{
		Profile profile = ExecutionPlan.resolveRunnerProfile(runner, temporaryRoot);
		return create(json, temporaryRoot, suitePath, promptPath, harness, contextPath, runner, assembly, retainedDirectory,
			profile, profile, null);
	}

	/**
	 * Creates a record whose retained runner artifact contains an immutable candidate bundle.
	 *
	 * @param json the preparation operation's JSON mapper
	 * @param temporaryRoot the CAT work temporary directory that owns the record
	 * @param suitePath the version-controlled test suite
	 * @param candidate the rules being measured at their installed paths
	 * @param harness the selected harness identifier
	 * @param contextPath the independent-validator context
	 * @param runner the selected harness runner launcher
	 * @param assembly the verified build inputs and runner artifact
	 * @param retainedDirectory the initially absent evidence directory
	 * @return the workspace, plan, and run record that form one ready evaluation
	 * @throws IOException if a prerequisite cannot be verified or an owned artifact cannot be created
	 */
	public static ObjectNode candidate(JsonMapper json, Path temporaryRoot, Path suitePath,
		CandidateBundle.Candidate candidate,
		String harness, Path contextPath, Path runner, ObjectNode assembly, Path retainedDirectory) throws IOException
	{
		Profile profile = ExecutionPlan.resolveRunnerProfile(runner, temporaryRoot);
		return create(json, temporaryRoot, suitePath, candidate.primary().source(), harness, contextPath, runner, assembly,
			retainedDirectory, profile, profile, candidate);
	}

	/**
	 * Creates a record with independently selected executor and validator profiles.
	 *
	 * @param json the preparation operation's JSON mapper
	 * @param temporaryRoot the CAT work temporary directory that owns the record
	 * @param suitePath the version-controlled test suite
	 * @param promptPath the prompt artifact under test
	 * @param harness the selected harness identifier
	 * @param contextPath the independent-validator context
	 * @param runner the selected harness runner launcher
	 * @param assembly the verified build inputs and runner artifact
	 * @param retainedDirectory the initially absent evidence directory
	 * @param executorProfile the profile that performs task work
	 * @param validatorProfile the profile that independently reviews task evidence
	 * @param candidate the optional immutable rule bundle installed for this evaluation
	 * @return the workspace, plan, and run record that form one ready evaluation
	 * @throws IOException if a prerequisite cannot be verified or an owned artifact cannot be created
	 */
	public static ObjectNode create(JsonMapper json, Path temporaryRoot, Path suitePath, Path promptPath,
		String harness, Path contextPath, Path runner, ObjectNode assembly, Path retainedDirectory, Profile executorProfile,
		Profile validatorProfile, CandidateBundle.Candidate candidate) throws IOException
	{
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(suitePath, "suitePath").isNotNull();
		requireThat(promptPath, "promptPath").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		requireThat(contextPath, "contextPath").isNotNull();
		requireThat(runner, "runner").isNotNull();
		requireThat(assembly, "assembly").isNotNull();
		requireThat(retainedDirectory, "retainedDirectory").isNotNull();
		requireThat(executorProfile, "executorProfile").isNotNull();
		requireThat(validatorProfile, "validatorProfile").isNotNull();
		PromptEvaluation.validateEvaluationInputs(suitePath, promptPath, harness, contextPath, runner);
		Path runnerArtifact = PromptEvaluation.path(PromptEvaluation.text(assembly, "runner_artifact",
			Path.of("assembly")));
		runnerArtifact = ExecutionPlan.verifyRunnerArtifact(runner, temporaryRoot, runnerArtifact);

		Path temporary = temporaryRoot.toAbsolutePath().normalize();
		Path retained = retainedDirectory.toAbsolutePath().normalize();
		if (!retained.startsWith(temporary))
		{
			throw new IOException("Uncommitted evaluation evidence must remain beneath the CAT work temp directory: " +
				retained);
		}
		if (Files.exists(retained, NOFOLLOW_LINKS))
			throw new IOException("Evaluation record directory already exists: " + retained);

		Path workspace = null;
		try
		{
			Files.createDirectories(retained);
			runnerArtifact = retainRunnerArtifact(temporary, runnerArtifact, candidate);
			workspace = retained.resolve("workspace");
			Files.createDirectories(workspace);
			ExecutionPlan.PreparedPlan preparedPlan = ExecutionPlan.write(json, retained,
				temporary, harness, runner, runnerArtifact, assembly, executorProfile, validatorProfile);
			Path plan = preparedPlan.plan();
			Path dependencyCache = preparedPlan.dependencyCache();
			Path run = retained.resolve("run.json");
			initialize(json, suitePath, promptPath, harness, executorProfile.label(), contextPath, run,
				PromptEvaluation.workRootForTemporaryResources(temporary));
			ObjectNode runRecord = PromptEvaluation.object(PromptEvaluation.read(run), "run");
			if (candidate != null)
			{
				ObjectNode candidateReceipt = CandidateBundle.receipt(json, candidate);
				runRecord.set("candidate_bundle", candidateReceipt);
				runRecord.put("candidate_bundle_sha256", candidate.sha256());
			}
			runRecord.put("workspace", workspace.toString());
			PromptEvaluation.write(run, runRecord);
			PromptEvaluation.markOwnedRecord(retained, run);
			Files.writeString(workspace.resolve(EvaluationWorkspaceLifecycle.EVIDENCE_MARKER),
				run.toAbsolutePath().normalize() + System.lineSeparator(), StandardCharsets.UTF_8);

			ObjectNode result = json.createObjectNode();
			result.put("workspace", workspace.toString());
			result.put("run", run.toString());
			result.put("execution_plan", plan.toString());
			result.put("executor_owner", executorProfile.owner());
			result.put("executor_model", executorProfile.model());
			result.put("executor_effort", executorProfile.effort());
			result.put("validator_owner", validatorProfile.owner());
			result.put("validator_model", validatorProfile.model());
			result.put("validator_effort", validatorProfile.effort());
			result.put("dependency_cache", dependencyCache.toString());
			return result;
		}
		catch (IOException | RuntimeException e)
		{
			if (workspace != null && Files.exists(workspace, NOFOLLOW_LINKS))
				TemporaryDirectories.deleteRecursively(workspace);
			if (Files.exists(retained, NOFOLLOW_LINKS))
				TemporaryDirectories.deleteRecursively(retained);
			throw e;
		}
	}

	/**
	 * Retains a runner artifact whose routed rules include the exact evaluated candidate.
	 *
	 * @param temporaryRoot the CAT work temporary directory
	 * @param source the verified packaged runner artifact selected for this evaluation
	 * @param candidate the candidate bundle, or {@code null} for the packaged prompt set
	 * @return the immutable content-addressed copy
	 * @throws IOException if the artifact or candidate cannot be copied
	 */
	private static Path retainRunnerArtifact(Path temporaryRoot, Path source,
		CandidateBundle.Candidate candidate) throws IOException
	{
		return RunnerArtifactStore.retain(PromptEvaluation.workRootForTemporaryResources(temporaryRoot), source,
			candidate);
	}
}
