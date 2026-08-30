package io.github.cowwoc.cat.common;

import io.github.cowwoc.cat.common.evaluation.CandidateController;
import io.github.cowwoc.cat.common.evaluation.CandidateLifecycle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link CandidateController}.
 */
public final class CandidateControllerTest
{
	private static final CandidateLifecycle.Access ACCESS = new CandidateLifecycle.Access()
	{
		@Override
		public void validateCandidateState(Path temporaryRoot, Path candidateStatePath) throws IOException
		{
			PromptEvaluation.validateDurableCandidateStatePath(temporaryRoot, candidateStatePath);
		}

		@Override
		public void validateMaximumConcurrency(int maximumConcurrency) throws IOException
		{
			PromptEvaluation.validateMaximumConcurrency(maximumConcurrency);
		}
	};

	/**
	 * Creates the test fixture.
	 */
	public CandidateControllerTest()
	{
	}

	/**
	 * Verifies a completed controller retains its selected transition identity and terminal status.
	 *
	 * @throws IOException if fixture I/O or status reading fails
	 */
	@Test
	public void recordsCompletedTransitionIdentity() throws IOException
	{
		try (ToolScope scope = new ToolScope();
		TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-controller"));
			Path state = root.resolve("candidate-state.json");
			Path suite = PromptEvaluationFixtures.writeSuite(root);
			Path prompt = write(root, "prompt.md", "prompt\n");
			Path context = write(root, "context.md", "context\n");
			Path runner = write(root, "runner", "runner\n");
			Path buildPlan = write(root, "build-plan.json", "plan\n");

			try (CandidateController controller = CandidateController.start(scope.json(), ACCESS, state))
			{
				controller.identify(ACCESS, suite, prompt, "fixture", context, runner, buildPlan, 1);
				controller.publish("running_regression_check");
				ObjectNode activeStatus = CandidateController.activeStatus(state);
				requireThat(activeStatus.path("controller_state").asString(), "activeState").isEqualTo("active");
				requireThat(activeStatus.path("current_phase").asString(), "activePhase").
					isEqualTo("running_regression_check");
				controller.complete();
			}

			ObjectNode status = PromptEvaluation.object(PromptEvaluation.read(
				CandidateController.statusPath(state)), "candidate controller status");
			requireThat(status.path("controller_state").asString(), "controllerState").isEqualTo("completed");
			requireThat(status.path("current_phase").asString(), "currentPhase").isEqualTo("terminal");
			requireThat(status.path("evaluation_identity").path("suite_sha256").asString(), "suiteFingerprint").
			isEqualTo(PromptEvaluation.suiteFingerprint(suite));
			requireThat(status.path("evaluation_identity").path("maximum_concurrency").asInt(), "maximumConcurrency").
				isEqualTo(1);
			requireThat(CandidateController.activeStatus(state), "activeStatusAfterCompletion").isNull();
		}
	}

	/**
	 * Writes one controller input fixture.
	 *
	 * @param root the fixture root
	 * @param name the root-relative file name
	 * @param contents the input content
	 * @return the created input
	 * @throws IOException if fixture I/O fails
	 */
	private static Path write(Path root, String name, String contents) throws IOException
	{
		Path file = root.resolve(name);
		Files.writeString(file, contents);
		return file;
	}
}
