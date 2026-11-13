package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.fakes.test.RequestingRun;
import com.holdmyspot.cat.common.runner.LaunchRefusedException;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that a CAT command that starts sessions inside a run, such as the prompt optimizer, starts each through the
 * run's runner with {@link SessionRequestForwarder#serve}, and that it is refused before it changes anything when the
 * run allows no further session.
 */
public final class ServedSessionTest
{
	private static final String COMMAND = "The prompt optimizer";
	private static final String RECORDS = """
		{"type":"turn","prompt":"task"}
		{"type":"complete"}
		""";
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public ServedSessionTest()
	{
	}

	/**
	 * Verifies that a served session gets, as copies inside the request, the workspace files and read-only evidence
	 * that the command names, together with its further denied reads, instruction files, session role,
	 * tool-output token limit and the command's CAT candidate as a plugin; that the command receives the session's
	 * records; and that the session's kept run, without its home, lands in the command's run root, whose run has its
	 * artifacts directory.
	 *
	 * @throws IOException if fixture or session I/O fails
	 */
	@Test(timeOut = 30_000)
	public void servesNarrowedSessionAndKeepsItsRun() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "served-")).toRealPath();
			Inputs inputs = new Inputs(root);
			Path denied = root.resolve("held-out");
			try (RequestingRun parent = new RequestingRun(root.resolve("parent"), jvm.fileLocks(), 1))
			{
				List<String> arguments = new ArrayList<>(inputs.arguments());
				arguments.addAll(List.of("--workspace", inputs.workspace.toString(), "--read-only-evidence",
					inputs.evidence.toString(), "--deny-read", denied.toString(), "--instruction-files", "claude-md",
					"--tool-output-token-limit", "4000", "--session-role", "judge", "--artifact", inputs.candidate.toString(),
					"--install-cat"));
				StringWriter output = new StringWriter();

				SessionRequestForwarder.serve(COMMAND, "claude", inputs.ownArtifact, arguments,
					new StringReader(RECORDS), output, parent.requests(), bounded());

				List<String> types = output.toString().lines().map(line -> json.readTree(line).path("type").asString()).
					toList();
				requireThat(types, "types").isEqualTo(List.of("started", "turn_completed", "completed"));
				String runId = json.readTree(output.toString().lines().findFirst().orElseThrow()).path("run_id").
					asString();
				Path kept = inputs.runRoot.resolve("claude").resolve(runId);
				requireThat(Files.isDirectory(kept.resolve("artifacts")), "artifacts").isTrue();
				requireThat(Files.readString(kept.resolve("project/result.txt")), "result").isEqualTo("result");
				requireThat(Files.exists(kept.resolve("home")), "home").isFalse();

				requireThat(parent.launches().size(), "launches").isEqualTo(1);
				List<String> launch = parent.launches().getFirst();
				requireThat(Files.readString(Path.of(value(launch, "--workspace")).resolve("notes.txt")), "workspace").
					isEqualTo("notes");
				requireThat(Files.readString(Path.of(value(launch, "--read-only-evidence"))), "evidence").
					isEqualTo("evidence");
				requireThat(launch, "launch").contains(denied.toString()).containsAll(List.of("--install-cat",
					"--instruction-files", "claude-md", "--tool-output-token-limit", "4000", "--session-role", "judge"));
				requireThat(Files.readString(Path.of(value(launch, "--plugin")).resolve("marker")), "plugin").
					isEqualTo("candidate");
				JsonNode response = onlyResponse(parent);
				requireThat(response.path("status").asString(), "status").isEqualTo("completed");
				requireThat(response.path("run").asString(), "run").isNotEmpty();
			}
		}
	}

	/**
	 * Supplies the session requests of a run whose runner refuses the next session, and whether the run first serves
	 * the one session it allows.
	 *
	 * @return the session requests of the run and whether one session is served first
	 */
	@DataProvider
	public Object[][] spentRuns()
	{
		return new Object[][]{{0, false}, {1, true}};
	}

	/**
	 * Verifies that a run that allows no session, or whose sessions are spent, refuses the next session with the
	 * message that names the caller's options that would allow it, before the command publishes a request or writes
	 * into its run root.
	 *
	 * @param sessionRequests how many sessions the run allows
	 * @param serveFirst      indicates whether the run serves one session before
	 * @throws IOException if fixture or session I/O fails
	 */
	@Test(dataProvider = "spentRuns", timeOut = 30_000)
	public void refusesBeforeAnyChange(int sessionRequests, boolean serveFirst) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "served-")).toRealPath();
			Inputs inputs = new Inputs(root);
			try (RequestingRun parent = new RequestingRun(root.resolve("parent"), jvm.fileLocks(), sessionRequests))
			{
				if (serveFirst)
				{
					SessionRequestForwarder.serve(COMMAND, "claude", inputs.ownArtifact, inputs.arguments(),
						new StringReader(RECORDS), new StringWriter(), parent.requests(), bounded());
				}
				List<String> requestsBefore = names(parent.requests());
				List<String> runsBefore = names(inputs.runRoot);
				String reason;
				if (sessionRequests == 0)
					reason = SessionRequestAccess.NO_REQUESTS_REASON;
				else
					reason = SessionRequestAccess.limitReason(sessionRequests);

				LaunchRefusedException refusal = refusal(parent, inputs);

				requireThat(refusal.getMessage(), "message").isEqualTo(SessionRequestAccess.refusalMessage(COMMAND,
					reason));
				requireThat(refusal.getMessage(), "message").contains("--session-requests").doesNotContain("--no-daemon");
				requireThat(names(parent.requests()), "requests").isEqualTo(requestsBefore);
				requireThat(names(inputs.runRoot), "runs").isEqualTo(runsBefore);
			}
		}
	}

	/**
	 * Verifies that a session that the run's runner refuses because the run's limit ran out after the command
	 * checked it is refused with the same message, so that a campaign pauses instead of counting a failure.
	 *
	 * @throws IOException if fixture or session I/O fails
	 */
	@Test(timeOut = 30_000)
	public void refusesWhenLimitRunsOutAfterCheck() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "served-")).toRealPath();
			Inputs inputs = new Inputs(root);
			try (RequestingRun parent = new RequestingRun(root.resolve("parent"), jvm.fileLocks(), 1))
			{
				SessionRequestForwarder.serve(COMMAND, "claude", inputs.ownArtifact, inputs.arguments(),
					new StringReader(RECORDS), new StringWriter(), parent.requests(), bounded());
				// The limits that the command checks do not yet show the session that another command started
				Files.delete(parent.responses().resolve(SessionRequestAccess.LIMITS_FILE));

				LaunchRefusedException refusal = refusal(parent, inputs);

				requireThat(refusal.getMessage(), "message").isEqualTo(SessionRequestAccess.refusalMessage(COMMAND,
					SessionRequestAccess.limitReason(1)));
				requireThat(parent.launches().size(), "launches").isEqualTo(1);
			}
		}
	}

	/**
	 * Verifies that a served session whose workspace does not exist fails before the command publishes a request.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(timeOut = 30_000)
	public void refusesMissingInput() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "served-")).toRealPath();
			Inputs inputs = new Inputs(root);
			try (RequestingRun parent = new RequestingRun(root.resolve("parent"), jvm.fileLocks(), 1))
			{
				List<String> arguments = new ArrayList<>(inputs.arguments());
				arguments.addAll(List.of("--workspace", root.resolve("missing").toString()));
				try
				{
					SessionRequestForwarder.serve(COMMAND, "claude", inputs.ownArtifact, arguments,
						new StringReader(RECORDS), new StringWriter(), parent.requests(), bounded());
					throw new AssertionError("A missing workspace was sent");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").contains(root.resolve("missing").toString());
				}
				requireThat(names(parent.requests()), "requests").isEmpty();
				requireThat(parent.launches(), "launches").isEmpty();
			}
		}
	}

	/**
	 * Supplies launches that a served session refuses, each with the option that it lacks or must not name.
	 *
	 * @return the options to add and those to remove
	 */
	@DataProvider
	public Object[][] invalidLaunches()
	{
		return new Object[][]{{List.of("--input", "records.jsonl"), List.of()},
			{List.of("--conversation", "folder"), List.of()}, {List.of(), List.of("--keep-run")}};
	}

	/**
	 * Verifies that a served session takes its records from the command and keeps its run in the command's run root,
	 * so a launch that names other records or keeps no run is refused before it publishes a request.
	 *
	 * @param added   the options that the launch adds
	 * @param removed the options that the launch leaves out
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "invalidLaunches", timeOut = 30_000)
	public void refusesLaunchThatItCannotServe(List<String> added, List<String> removed) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "served-")).toRealPath();
			Inputs inputs = new Inputs(root);
			try (RequestingRun parent = new RequestingRun(root.resolve("parent"), jvm.fileLocks(), 1))
			{
				List<String> arguments = new ArrayList<>(inputs.arguments());
				arguments.removeAll(removed);
				arguments.addAll(added);
				try
				{
					SessionRequestForwarder.serve(COMMAND, "claude", inputs.ownArtifact, arguments,
						new StringReader(RECORDS), new StringWriter(), parent.requests(), bounded());
					throw new AssertionError("The launch was served");
				}
				catch (IllegalArgumentException e)
				{
					requireThat(e.getMessage(), "message").contains("served session");
				}
				requireThat(names(parent.requests()), "requests").isEmpty();
			}
		}
	}

	/**
	 * Serves a session that the run must refuse.
	 *
	 * @param parent the run
	 * @param inputs the session's inputs
	 * @return the refusal
	 * @throws IOException if the session fails for another reason
	 */
	private static LaunchRefusedException refusal(RequestingRun parent, Inputs inputs) throws IOException
	{
		try
		{
			SessionRequestForwarder.serve(COMMAND, "claude", inputs.ownArtifact, inputs.arguments(),
				new StringReader(RECORDS), new StringWriter(), parent.requests(), bounded());
		}
		catch (LaunchRefusedException e)
		{
			return e;
		}
		throw new AssertionError("The run served a session that it does not allow");
	}

	/**
	 * Returns the response of the run's only request.
	 *
	 * @param parent the run
	 * @return the response
	 * @throws IOException if the run has no single response
	 */
	private JsonNode onlyResponse(RequestingRun parent) throws IOException
	{
		try (Stream<Path> entries = Files.list(parent.responses()))
		{
			List<Path> directories = entries.filter(Files::isDirectory).toList();
			requireThat(directories.size(), "responses").isEqualTo(1);
			return json.readTree(directories.getFirst().resolve("response.json").toFile());
		}
	}

	/**
	 * Returns a pause that fails a session that does not end within a bounded number of checks.
	 *
	 * @return the pause
	 */
	private static Pause bounded()
	{
		AtomicInteger checks = new AtomicInteger();
		return () ->
		{
			if (checks.incrementAndGet() > 10_000_000)
				throw new IOException("The served session did not end");
			Thread.yield();
		};
	}

	/**
	 * Returns the sorted names in a directory.
	 *
	 * @param directory the directory
	 * @return the names, empty if the directory does not exist
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<String> names(Path directory) throws IOException
	{
		if (!Files.exists(directory))
			return List.of();
		try (Stream<Path> entries = Files.list(directory))
		{
			return entries.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	/**
	 * Returns the value of an option.
	 *
	 * @param arguments the options
	 * @param name      the option's name
	 * @return its value
	 */
	private static String value(List<String> arguments, String name)
	{
		int index = arguments.indexOf(name);
		requireThat(index, name).isNotNegative();
		return arguments.get(index + 1);
	}

	/**
	 * The files and directories that a command names to its served sessions.
	 */
	private static final class Inputs
	{
		final Path project;
		final Path runRoot;
		final Path workspace;
		final Path evidence;
		final Path candidate;
		final Path ownArtifact;

		/**
		 * Creates the inputs.
		 *
		 * @param root the folder that holds them
		 * @throws IOException if they cannot be written
		 */
		Inputs(Path root) throws IOException
		{
			project = Files.createDirectories(root.resolve("project"));
			Files.writeString(project.resolve("marker"), "project bytes");
			runRoot = root.resolve("runs");
			workspace = Files.createDirectories(root.resolve("workspace"));
			Files.writeString(workspace.resolve("notes.txt"), "notes");
			evidence = Files.writeString(root.resolve("evidence.txt"), "evidence");
			candidate = Files.createDirectories(root.resolve("candidate"));
			Files.writeString(candidate.resolve("marker"), "candidate");
			ownArtifact = Files.createDirectories(root.resolve("own"));
		}

		/**
		 * Returns the options of a session that names only its project, run root, model and effort.
		 *
		 * @return the options
		 */
		List<String> arguments()
		{
			return List.of("--project", project.toString(), "--run-root", runRoot.toString(), "--keep-run", "--model",
				"model-a", "--effort", "effort-a");
		}
	}
}
