package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PromptEvaluation;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests the public command contract of {@link PromptEvaluation}.
 */
public final class PublicCommandsTest
{
	/**
	 * Creates the test fixture.
	 */
	public PublicCommandsTest()
	{
	}

	/**
	 * Verifies the command prints its usage without failing when help is requested.
	 *
	 * @throws IOException if output capture fails
	 */
	@Test
	public void printsHelp() throws IOException
	{
		String help = output("--help");
		requireThat(help, "help").contains("Usage: prompt-evaluation <command> [arguments]").
			contains("Evaluate a prompt revision").contains("Investigate a failed result").
			contains("precheck").contains("candidate-status").contains("help <command>").
			doesNotContain("goal-status").
			doesNotContain("begin <").doesNotContain("run-batch <").doesNotContain("record <run.json>").
			doesNotContain("validate-suite").doesNotContain("preflight").doesNotContain("advance").
			doesNotContain("terminate").doesNotContain("prepare-revision-review").
			doesNotContain("record-revision-review").doesNotContain("prepare-recurrence-review").
			doesNotContain("record-recurrence-review").doesNotContain("supersede-evaluation-epoch");
	}

	/**
	 * Verifies a public command explains only its own caller-facing inputs and result.
	 *
	 * @throws IOException if output capture fails
	 */
	@Test
	public void printsDetailedPublicCommandHelp() throws IOException
	{
		String help = output("help", "regression-check");
		String optionHelp = output("regression-check", "--help");
		String revisionReviewHelp = output("help", "revision-review");
		String lifecycleHelp = output("help", "lifecycle-soak");
		String materializationHelp = output("help", "materialize-candidate");
		String historicalMaterializationHelp = output("help", "materialize-candidate-git");
		requireThat(help, "help").contains("regression-check <smoke-run.json> <candidate-state.json>").
			contains("Runs every durable certification case once").doesNotContain("claim-trials").
			doesNotContain("run-batch");
		requireThat(optionHelp, "optionHelp").isEqualTo(help);
		requireThat(materializationHelp, "materializationHelp").
			contains("materialize-candidate <source-rules-directory>");
		requireThat(historicalMaterializationHelp, "historicalMaterializationHelp").
			contains("materialize-candidate-git <repository> <revision>");
		requireThat(lifecycleHelp, "lifecycleHelp").contains("lifecycle-soak <build-plan.json>").
			doesNotContain("<build-plan.json> <runner>");
		requireThat(revisionReviewHelp, "revisionReviewHelp").
			contains("revision-review <candidate-state.json> <proposed-run.json>").
			contains("Runs the required independent review").doesNotContain("prepare-revision-review").
			doesNotContain("record-revision-review");
	}

	/**
	 * Verifies precheck is the public operation that validates a suite and its deterministic controls together.
	 */
	@Test
	public void dispatchesPrecheck()
	{
		String[] invocation = {"precheck", "scenario-suite-directory"};
		StringBuilder dispatched = new StringBuilder();

		try
		{
			PublicCommands.execute(invocation, args -> dispatched.append(String.join(" ", args)));
		}
		catch (IOException e)
		{
			throw new AssertionError("Precheck dispatch must not perform workflow I/O", e);
		}

		requireThat(dispatched.toString(), "dispatched").isEqualTo("precheck scenario-suite-directory");
	}

/**
	 * Verifies candidate status is a complete public read-only operation.
	 */
	@Test
	public void dispatchesCandidateStatus()
	{
		String[] invocation = {"candidate-status", "candidate-state.json"};
		StringBuilder dispatched = new StringBuilder();

		try
		{
			PublicCommands.execute(invocation, args -> dispatched.append(String.join(" ", args)));
		}
		catch (IOException e)
		{
			throw new AssertionError("Candidate-status dispatch must not perform workflow I/O", e);
		}

		requireThat(dispatched.toString(), "dispatched").isEqualTo("candidate-status candidate-state.json");
	}

	/**
	 * Verifies revision review is a complete public operation, not a pair of callable workflow stages.
	 */
	@Test
	public void dispatchesRevisionReview()
	{
		String[] invocation = {"revision-review", "candidate-state.json", "proposed-run.json", "review-directory"};
		StringBuilder dispatched = new StringBuilder();

		try
		{
			PublicCommands.execute(invocation, args -> dispatched.append(String.join(" ", args)));
		}
		catch (IOException e)
		{
			throw new AssertionError("Revision-review dispatch must not perform workflow I/O", e);
		}

		requireThat(dispatched.toString(), "dispatched").isEqualTo(
			"revision-review candidate-state.json proposed-run.json review-directory");
	}

	/**
	 * Verifies runtime revalidation is one complete public operation, rather than an exposed authorization stage.
	 */
	@Test
	public void dispatchesRuntimeRevalidation()
	{
		String[] invocation = {"revalidate-runtime", "candidate-state.json", "smoke-run.json"};
		StringBuilder dispatched = new StringBuilder();

		try
		{
			PublicCommands.execute(invocation, args -> dispatched.append(String.join(" ", args)));
		}
		catch (IOException e)
		{
			throw new AssertionError("Runtime revalidation dispatch must not perform workflow I/O", e);
		}

		requireThat(dispatched.toString(), "dispatched").isEqualTo(
			"revalidate-runtime candidate-state.json smoke-run.json");
	}

	/**
	 * Verifies revision review rejects an invocation that omits a required caller-facing input.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*revision-review <candidate-state.json> <proposed-run.json>.*")
	public void rejectsIncompleteRevisionReview()
	{
		try
		{
			PublicCommands.execute(new String[]{"revision-review", "candidate-state.json", "proposed-run.json"},
				ignored ->
				{
					throw new AssertionError("Invalid invocation must not dispatch workflow composition");
				});
		}
		catch (IOException e)
		{
			throw new AssertionError("Argument validation must not perform workflow I/O", e);
		}
	}

	/**
	 * Verifies runtime revalidation rejects an invocation that omits its passed smoke evidence.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*revalidate-runtime <candidate-state.json> <passed-smoke-run.json>.*")
	public void rejectsIncompleteRuntimeRevalidation()
	{
		try
		{
			PublicCommands.execute(new String[]{"revalidate-runtime", "candidate-state.json"}, ignored ->
			{
				throw new AssertionError("Invalid invocation must not dispatch workflow composition");
			});
		}
		catch (IOException e)
		{
			throw new AssertionError("Argument validation must not perform workflow I/O", e);
		}
	}

	/**
	 * Verifies lifecycle soak accepts the build plan without a separately supplied runner.
	 */
	@Test
	public void dispatchesLifecycleSoakFromBuildPlan()
	{
		String[] invocation = {"lifecycle-soak", "build-plan.json"};
		StringBuilder dispatched = new StringBuilder();

		try
		{
			PublicCommands.execute(invocation, args -> dispatched.append(String.join(" ", args)));
		}
		catch (IOException e)
		{
			throw new AssertionError("Lifecycle soak dispatch must not perform workflow I/O", e);
		}

		requireThat(dispatched.toString(), "dispatched").isEqualTo("lifecycle-soak build-plan.json");
	}

	/**
	 * Verifies callers cannot invoke an individual workflow stage through the public CLI.
	 *
	 * @throws IOException if fixture I/O or output capture fails
	 */
	@Test
	public void rejectsInternalWorkflowCommand() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation-hidden-command"));
			Path state = write(root, "candidate-state.json", "unchanged\n");
			List<String> before = entries(root);
			for (String command : List.of("validate-suite", "preflight", "goal-status", "terminate", "assemble", "prepare",
				"evaluate", "begin", "bind-plan", "claim-trials",
				"prepare-trial", "prepare-validator", "execute", "run-batch", "smoke", "run-regression-check",
				"create-routing-diagnostic", "run-routing-matrix", "promote", "record-regression-check-signatures",
				"run-until-terminal", "handoff", "record", "decide", "prepare-revision-review",
				"record-revision-review", "prepare-recurrence-review", "record-recurrence-review",
				"authorize-revalidated-epoch", "supersede-evaluation-epoch"))
			{
				try
				{
					PromptEvaluation.main(new String[]{command, state.toString()});
					throw new AssertionError("Expected internal workflow command to be rejected: " + command);
				}
				catch (IllegalArgumentException e)
				{
					requireThat(e.getMessage(), "message").isEqualTo("Unknown prompt-evaluation command: " + command +
						". Use `prompt-evaluation --help` to list public commands.");
				}
			}
			requireThat(entries(root), "rootContents").isEqualTo(before);
			requireThat(Files.readString(state), "candidateState").isEqualTo("unchanged\n");
		}
	}

	/**
	 * Lists the names of the fixture root's direct children.
	 *
	 * @param root the fixture root
	 * @return its sorted direct-child names
	 * @throws IOException if the fixture cannot be listed
	 */
	private static List<String> entries(Path root) throws IOException
	{
		try (Stream<Path> entries = Files.list(root))
		{
			return entries.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	/**
	 * Collects output from the public command boundary.
	 *
	 * @param args the public command invocation
	 * @return its UTF-8 standard output
	 * @throws IOException if command I/O fails
	 */
	private static String output(String... args) throws IOException
	{
		StringBuilder output = new StringBuilder();
		PublicCommands.execute(args, ignored ->
		{
			throw new AssertionError("Help must not execute workflow composition");
		}, line -> output.append(line).append('\n'));
		return output.toString();
	}

	/**
	 * Writes one fixture file.
	 *
	 * @param root the fixture root
	 * @param name the filename
	 * @param contents the file contents
	 * @return the written path
	 * @throws IOException if fixture I/O fails
	 */
	private static Path write(Path root, String name, String contents) throws IOException
	{
		Path path = root.resolve(name);
		Files.writeString(path, contents);
		return path;
	}
}
