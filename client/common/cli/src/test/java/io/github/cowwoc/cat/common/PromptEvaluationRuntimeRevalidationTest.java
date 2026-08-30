package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.CandidateState;
import io.github.cowwoc.cat.common.evaluation.RuntimeRevalidation;

import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.recordedFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests runtime revalidation for a frozen evaluation epoch.
 */
public final class PromptEvaluationRuntimeRevalidationTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationRuntimeRevalidationTest()
	{
	}

	/**
	 * Verifies an execution-identity rejection directs a caller with a passed smoke run to the revalidation command.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*workflow-owned revalidation.*")
	public void directsEpochChangeToRevalidation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path proposedRun = recordedFailure(root, suite, "requested-change", "deterministic-tool", "failed");
			ObjectNode proposed = (ObjectNode) scope.json().readTree(proposedRun.toFile());
			proposed.put("evaluation_epoch_sha256", "replacement-execution-epoch");
			Files.writeString(proposedRun, scope.json().writeValueAsString(proposed));

			Path state = root.resolve("candidate-state.json");
			ObjectNode candidateState = scope.json().createObjectNode();
			candidateState.put("evaluation_epoch_sha256", "original-execution-epoch");
			candidateState.putArray("failure_signatures");
			Files.writeString(state, scope.json().writeValueAsString(candidateState));

			CandidateState.validateCandidateStateBeforeCheck(state, proposedRun);
		}
	}

	/**
	 * Verifies a passed smoke run can revalidate the same unfinished candidate after its failed check removed the prior
	 * promotion.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void revalidatesUnpromotedCandidate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path smokeRun = recordedFailure(root, suite, "requested-change", null, "passed");
			ObjectNode smoke = (ObjectNode) scope.json().readTree(smokeRun.toFile());
			smoke.put("evaluation_epoch_sha256", "replacement-execution-epoch");
			smoke.putObject("evaluation_epoch").put("runner_sha256", "replacement-runner");
			smoke.put("assembled_prompt_route_sha256", "unchanged-candidate-route");
			smoke.put("mode", "smoke");
			smoke.set("smoke_result", scope.json().createObjectNode().put("classification", "non_certifying_smoke").
				put("case_id", "requested-change").put("trial", 1));
			Files.writeString(smokeRun, scope.json().writeValueAsString(smoke));

			Path failedCheck = root.resolve("failed-check.json");
			ObjectNode failed = smoke.deepCopy();
			failed.put("evaluation_epoch_sha256", "original-execution-epoch");
			((ObjectNode) failed.path("evaluation_epoch")).put("runner_sha256", "original-runner");
			Files.writeString(failedCheck, scope.json().writeValueAsString(failed));

			Path state = root.resolve("candidate-state.json");
			ObjectNode candidateState = scope.json().createObjectNode();
			candidateState.put("evaluation_epoch_sha256", "original-execution-epoch");
			candidateState.put("latest_check", failedCheck.toString());
			candidateState.putArray("failure_signatures");
			Files.writeString(state, scope.json().writeValueAsString(candidateState));

			ObjectNode authorization = RuntimeRevalidation.revalidate(state, smokeRun);

			requireThat(authorization.path("replacement_run").asString(), "replacementRun").isEqualTo(
				smokeRun.toAbsolutePath().normalize().toString());
			CandidateState.validateCandidateStateBeforeCheck(state, smokeRun);
		}
	}

	/**
	 * Verifies the public revalidation operation authorizes a passed smoke run's replacement execution epoch without
	 * claiming that planned evaluator maintenance was a defect.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void authorizesRevalidatedExecutionEpoch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path smokeRun = recordedFailure(root, suite, "requested-change", null, "passed");
			ObjectNode smoke = (ObjectNode) scope.json().readTree(smokeRun.toFile());
			smoke.put("evaluation_epoch_sha256", "replacement-execution-epoch");
			smoke.putObject("evaluation_epoch").put("runner_sha256", "replacement-runner");
			smoke.put("assembled_prompt_route_sha256", "unchanged-candidate-route");
			smoke.put("mode", "smoke");
			smoke.set("smoke_result", scope.json().createObjectNode().put("classification", "non_certifying_smoke").
				put("case_id", "requested-change").put("trial", 1));
			Files.writeString(smokeRun, scope.json().writeValueAsString(smoke));

			Path previousRun = root.resolve("previous-run.json");
			ObjectNode previous = smoke.deepCopy();
			previous.put("evaluation_epoch_sha256", "original-execution-epoch");
			((ObjectNode) previous.path("evaluation_epoch")).put("runner_sha256", "original-runner");
			Files.writeString(previousRun, scope.json().writeValueAsString(previous));

			Path state = root.resolve("candidate-state.json");
			ObjectNode candidateState = scope.json().createObjectNode();
			candidateState.put("evaluation_epoch_sha256", "original-execution-epoch");
			ObjectNode promotion = candidateState.putObject("current_promotion");
			promotion.put("prompt_sha256", smoke.path("prompt_sha256").asString());
			promotion.put("check_run", previousRun.toString());
			candidateState.putArray("failure_signatures");
			Files.writeString(state, scope.json().writeValueAsString(candidateState));

			PromptEvaluation.main(new String[]{"revalidate-runtime", state.toString(), smokeRun.toString()});

			ObjectNode revalidatedState = (ObjectNode) scope.json().readTree(state.toFile());
			requireThat(revalidatedState.path("evaluation_epoch_supersessions").toString(), "authorization").
				contains("\"kind\":\"revalidated-runtime\"").contains(
					"\"replacement_run\":\"" + smokeRun.toAbsolutePath().normalize() + "\"");
			CandidateState.validateCandidateStateBeforeCheck(state, smokeRun);
		}
	}
}
