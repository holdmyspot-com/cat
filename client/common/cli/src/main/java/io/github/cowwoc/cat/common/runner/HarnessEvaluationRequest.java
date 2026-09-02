package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

/**
 * Describes one isolated harness evaluation that must retain evidence for a later evaluator decision.
 *
 * @param launcher the harness bridge launcher
 * @param artifact the complete harness artifact to execute
 * @param project the isolated project supplied to the harness
 * @param profile the selected model and reasoning effort
 * @param input the complete JSONL bridge input
 * @param transcript the retained bridge transcript destination
 * @param receipt the retained-evidence receipt destination
 * @param installCat whether the harness installs the artifact's CAT content
 * @param readOnlyEvidence immutable evidence roots available to the harness
 */
public record HarnessEvaluationRequest(Path launcher, Path artifact, Path project, HarnessModelProfile profile,
	String input, Path transcript, Path receipt, boolean installCat, List<Path> readOnlyEvidence)
{
	/**
	 * Validates the evaluation inputs before a runner creates a native process.
	 *
	 * @param launcher the harness bridge launcher
	 * @param artifact the complete harness artifact to execute
	 * @param project the isolated project supplied to the harness
	 * @param profile the selected model and reasoning effort
	 * @param input the complete JSONL bridge input
	 * @param transcript the retained bridge transcript destination
	 * @param receipt the retained-evidence receipt destination
	 * @param installCat whether the harness installs the artifact's CAT content
	 * @param readOnlyEvidence immutable evidence roots available to the harness
	 */
	public HarnessEvaluationRequest
	{
		requireThat(launcher, "launcher").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(profile, "profile").isNotNull();
		requireThat(input, "input").isNotBlank();
		requireThat(transcript, "transcript").isNotNull();
		requireThat(receipt, "receipt").isNotNull();
		requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
		launcher = launcher.toAbsolutePath().normalize();
		artifact = artifact.toAbsolutePath().normalize();
		project = project.toAbsolutePath().normalize();
		transcript = transcript.toAbsolutePath().normalize();
		receipt = receipt.toAbsolutePath().normalize();
		readOnlyEvidence = readOnlyEvidence.stream().map(path -> path.toAbsolutePath().normalize()).toList();
		if (readOnlyEvidence.stream().anyMatch(path -> !Files.isDirectory(path)))
			throw new IllegalArgumentException("readOnlyEvidence must contain only directories");
	}

	/**
	 * Returns the provider model identifier supplied to the harness adapter.
	 *
	 * @return the selected provider model identifier
	 */
	public String model()
	{
		return profile.model();
	}

	/**
	 * Returns the reasoning-effort value supplied to the harness adapter.
	 *
	 * @return the selected external reasoning-effort spelling
	 */
	public String effort()
	{
		return profile.externalEffort();
	}
}
