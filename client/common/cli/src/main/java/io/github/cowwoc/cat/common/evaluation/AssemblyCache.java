package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PromptEvaluation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Locates and bounds durable reusable assembly receipts under CAT work state.
 */
public final class AssemblyCache
{
	private static final int MAXIMUM_RECEIPTS = 16;

	/**
	 * Prevents construction of this static receipt-cache utility.
	 */
	private AssemblyCache()
	{
	}

	/**
	 * Returns the receipt path reserved for one build plan.
	 *
	 * @param workRoot the CAT work root that owns reusable assembly receipts
	 * @param planPath the selected build plan
	 * @return the receipt path
	 * @throws IOException if the plan path cannot be fingerprinted
	 */
	public static Path path(Path workRoot, Path planPath) throws IOException
	{
		return workRoot.toAbsolutePath().normalize().resolve(WorkDirectories.ASSEMBLIES).
			resolve(PromptEvaluation.sha256(planPath.toString()) + ".json");
	}

	/**
	 * Keeps reusable assembly receipts bounded while preserving the receipt required by the active build.
	 *
	 * @param cache the evaluator-owned assembly receipt directory
	 * @param currentReceipt the receipt required by the active operation
	 * @throws IOException if old receipts cannot be removed
	 */
	public static void prune(Path cache, Path currentReceipt) throws IOException
	{
		List<Path> receipts;
		try (Stream<Path> entries = Files.list(cache))
		{
			receipts = entries.filter(path -> path.getFileName().toString().endsWith(".json")).
				filter(path -> !path.equals(currentReceipt)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		}
		while (receipts.size() >= MAXIMUM_RECEIPTS)
		{
			Path oldest = receipts.getFirst();
			for (Path candidate : receipts)
			{
				if (Files.getLastModifiedTime(candidate).compareTo(Files.getLastModifiedTime(oldest)) < 0)
					oldest = candidate;
			}
			Files.deleteIfExists(oldest);
			receipts.remove(oldest);
		}
	}

	/**
	 * Reads a receipt only when its immutable inputs and verified artifact still match the current assembly request.
	 *
	 * @param json the assembly operation's JSON mapper
	 * @param temporaryRoot the CAT work directory that owns transient runner-query output
	 * @param receipt the receipt to inspect
	 * @param planFingerprint the current plan fingerprint
	 * @param inputFingerprint the current declared-input fingerprint
	 * @param runnerFingerprint the current runner fingerprint
	 * @param declaredArtifact the artifact the plan expects
	 * @param runner the runner that verifies the artifact
	 * @return a reusable result, or {@code null} when a rebuild is required
	 * @throws IOException if a matching receipt cannot be verified
	 */
	public static ObjectNode reusable(JsonMapper json, Path temporaryRoot, Path receipt, String planFingerprint,
		String inputFingerprint, String runnerFingerprint, Path declaredArtifact, Path runner) throws IOException
	{
		requireThat(json, "json").isNotNull();
		if (!Files.isRegularFile(receipt))
			return null;
		ObjectNode cached = PromptEvaluation.object(PromptEvaluation.read(receipt), "assembly receipt");
		if (!cached.path("build_plan_sha256").asString().equals(planFingerprint) ||
			!cached.path("build_inputs_sha256").asString().equals(inputFingerprint) ||
			cached.path("execution_inputs_sha256").asString().isBlank() ||
			!cached.path("runner_sha256").asString().equals(runnerFingerprint) ||
			!cached.path("runner_artifact").asString().equals(declaredArtifact.toString()) ||
			!Files.isDirectory(declaredArtifact))
		{
			return null;
		}
		if (!cached.path("runner_artifact_sha256").asString().equals(
			PromptEvaluation.fingerprintArtifact(declaredArtifact)))
			return null;
		if (!cached.path("prompt_bundle_sha256").asString().equals(
			PromptEvaluation.fingerprintPromptBundle(declaredArtifact)))
			return null;
		Path artifact = ExecutionPlan.verifyRunnerArtifact(runner, temporaryRoot, declaredArtifact);
		ObjectNode result = json.createObjectNode();
		result.put("build_plan", cached.path("build_plan").asString());
		result.put("build_plan_sha256", cached.path("build_plan_sha256").asString());
		result.put("build_inputs_sha256", cached.path("build_inputs_sha256").asString());
		result.put("execution_inputs_sha256", cached.path("execution_inputs_sha256").asString());
		result.put("runner_sha256", cached.path("runner_sha256").asString());
		result.put("working_directory", cached.path("working_directory").asString());
		result.put("runner_artifact", artifact.toString());
		result.put("runner_artifact_sha256", cached.path("runner_artifact_sha256").asString());
		result.put("prompt_bundle_sha256", cached.path("prompt_bundle_sha256").asString());
		result.put("reused", true);
		result.put("repackaged_prompt_bundle", false);
		return result;
	}

	/**
	 * Determines whether the prior receipt proves that the runtime is unchanged for a prompt-only package update.
	 *
	 * @param temporaryRoot the CAT work directory that owns transient runner-query output
	 * @param receipt the cached assembly receipt
	 * @param planFingerprint the current build-plan fingerprint
	 * @param executionInputFingerprint the current runtime input fingerprint
	 * @param runnerFingerprint the selected runner launcher fingerprint
	 * @param declaredArtifact the artifact that a prompt package update would modify
	 * @param runner the runner that verifies the unchanged runtime
	 * @return {@code true} if a prompt-only package command may update the artifact
	 * @throws IOException if the runner cannot verify an otherwise matching artifact
	 */
	public static boolean canRepackage(Path temporaryRoot, Path receipt, String planFingerprint,
		String executionInputFingerprint, String runnerFingerprint, Path declaredArtifact, Path runner)
		throws IOException
	{
		if (!Files.isRegularFile(receipt))
			return false;
		ObjectNode cached = PromptEvaluation.object(PromptEvaluation.read(receipt), "assembly receipt");
		if (!cached.path("build_plan_sha256").asString().equals(planFingerprint) ||
			!cached.path("execution_inputs_sha256").asString().equals(executionInputFingerprint) ||
			!cached.path("runner_sha256").asString().equals(runnerFingerprint) ||
			!cached.path("runner_artifact").asString().equals(declaredArtifact.toString()) ||
			!Files.isDirectory(declaredArtifact))
		{
			return false;
		}
		ExecutionPlan.verifyRunnerArtifact(runner, temporaryRoot, declaredArtifact);
		return true;
	}
}
