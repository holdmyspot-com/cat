package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.ProcessExecution;
import io.github.cowwoc.cat.common.PromptEvaluation;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds and verifies the reusable runner artifact for a prompt evaluation.
 */
public final class Assembly
{
	/**
	 * Prevents construction of this stateless assembly owner.
	 */
	private Assembly()
	{
	}

	/**
	 * Builds a runner artifact or reuses a receipt whose immutable inputs still match.
	 *
	 * @param json the assembly operation's JSON mapper
	 * @param temporaryRoot the CAT work temporary root for build diagnostics
	 * @param buildPlanPath the selected project-owned build plan
	 * @param runner the runner that verifies the artifact
	 * @return the verified assembly receipt
	 * @throws IOException if the plan, build, inputs, or artifact is invalid
	 */
	public static ObjectNode assemble(JsonMapper json, Path temporaryRoot, Path buildPlanPath,
		Path runner) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(buildPlanPath, "buildPlanPath").isNotNull();
		requireThat(runner, "runner").isNotNull();
		Path workRoot = PromptEvaluation.workRootForTemporaryResources(temporaryRoot);
		Path planPath = buildPlanPath.toAbsolutePath().normalize();
		if (!Files.isRegularFile(planPath, NOFOLLOW_LINKS))
			throw new IOException("Expected version-controlled build plan: " + planPath);
		if (!Files.isRegularFile(runner, NOFOLLOW_LINKS) || !Files.isExecutable(runner))
			throw new IOException("Expected executable harness runner: " + runner);

		ObjectNode plan = PromptEvaluation.object(PromptEvaluation.read(planPath), "build plan");
		Path workingDirectory = PromptEvaluation.planPath(planPath.getParent(),
			PromptEvaluation.text(plan, "working_directory", planPath), "Build working directory");
		if (!Files.isDirectory(workingDirectory, NOFOLLOW_LINKS))
			throw new IOException("Build plan working directory does not exist: " + workingDirectory);
		Path declaredArtifact = PromptEvaluation.planPath(workingDirectory,
			PromptEvaluation.text(plan, "runner_artifact", planPath), "Runner artifact");
		List<String> command = CommandArguments.expand(plan.path("command"), "build plan", Map.of());
		List<String> promptCommand = CommandArguments.optional(plan.path("prompt_command"),
			"prompt bundle plan");
		Duration timeout = PromptEvaluation.buildTimeout(plan, planPath);
		List<Path> inputs = PromptEvaluation.buildInputs(plan, "inputs", planPath, workingDirectory, declaredArtifact,
			null);
		List<Path> executionInputs = PromptEvaluation.buildInputs(plan, "execution_inputs", planPath,
			workingDirectory, declaredArtifact, inputs);
		String planFingerprint = PromptEvaluation.sha256(planPath);
		String inputFingerprint = PromptEvaluation.fingerprintBuildInputs(workingDirectory, inputs);
		String executionInputFingerprint = PromptEvaluation.fingerprintBuildInputs(workingDirectory, executionInputs);
		String runnerFingerprint = PromptEvaluation.sha256(runner);
		Path receipt = AssemblyCache.path(workRoot, planPath);
		ObjectNode cached = PromptEvaluation.readReusableAssembly(json, temporaryRoot, receipt, planFingerprint,
			inputFingerprint, runnerFingerprint, declaredArtifact, runner);
		if (cached != null)
			return cached;

		boolean repackaged = !promptCommand.isEmpty() && PromptEvaluation.canRepackagePromptBundle(temporaryRoot,
			receipt, planFingerprint, executionInputFingerprint, runnerFingerprint, declaredArtifact, runner);
		List<String> selectedCommand = command;
		if (repackaged)
			selectedCommand = promptCommand;
		ProcessExecution.runBuild(temporaryRoot, selectedCommand, workingDirectory, timeout);
		PromptEvaluation.verifyUnchangedBuildInputs(planPath, workingDirectory, inputs, inputFingerprint,
			executionInputs, executionInputFingerprint);
		return PromptEvaluation.writeAssemblyReceipt(temporaryRoot, receipt, planPath, planFingerprint,
			inputFingerprint, executionInputFingerprint, runnerFingerprint, workingDirectory, declaredArtifact, runner,
			repackaged);
	}
}
