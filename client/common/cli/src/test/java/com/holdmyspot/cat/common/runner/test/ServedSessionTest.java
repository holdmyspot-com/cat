package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.LaunchRefusedException;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.fakes.test.RequestingNestedSession;
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
 * Tests that a CAT command that starts sessions inside a nested session, such as the prompt optimizer, starts each
 * through the nested session's runner with {@link SessionRequestForwarder#serve}, and that it is refused before it
 * changes anything when the nested session allows no further session.
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
	 * that the command names, together with its further denied reads, instruction files, session role, tool-output
	 * token limit and the command's CAT candidate as a plugin; that the command receives the session's records; and
	 * that the session's results copy, without its home, lands in the command's results folder, whose nested session has
	 * its artifacts directory.
	 *
	 * @throws IOException if fixture or session I/O fails
	 */
	@Test(timeOut = 30_000)
	public void servesNarrowedSessionAndKeepsResults() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "served-")).toRealPath();
			Inputs inputs = new Inputs(root);
			Path denied = root.resolve("held-out");
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), jvm.fileLocks(), 1))
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
				requireThat(types, "types").isEqualTo(List.of("started", "session_id", "turn_completed", "completed"));
				Path kept = inputs.resultsFolder.resolve("claude").resolve(RequestingNestedSession.SESSION_ID);
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
				requireThat(response.path("results_copy").asString(), "resultsCopy").isNotEmpty();
			}
		}
	}

	/**
	 * Verifies that a workspace that holds symbolic links whose targets lie inside it, such as a draft directory whose
	 * prompt test links a workspace file to a file of the draft, reaches the served session with each link as a link with
	 * the same target, so that a revision of a draft that holds links starts instead of being refused before the
	 * session reads the feedback.
	 *
	 * @throws IOException if fixture or session I/O fails
	 */
	@Test(timeOut = 30_000)
	public void carriesWorkspaceLinksThatStayInsideIt() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "served-")).toRealPath();
			Inputs inputs = new Inputs(root);
			Path tests = Files.createDirectories(inputs.workspace.resolve("cat-draft/.cat/tests/AGENTS"));
			Files.writeString(tests.resolve("arrival-board.md"), "board");
			Files.writeString(Files.createDirectories(tests.resolve("guides")).resolve("opening.md"), "opening");
			Path linked = Files.createDirectories(tests.resolve("shipment/workspace"));
			Files.createSymbolicLink(linked.resolve("arrival-board.md"), Path.of("../../arrival-board.md"));
			Files.createSymbolicLink(linked.resolve("guides"), Path.of("../../guides"));
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), jvm.fileLocks(), 1))
			{
				List<String> arguments = new ArrayList<>(inputs.arguments());
				arguments.addAll(List.of("--workspace", inputs.workspace.toString()));
				SessionRequestForwarder.serve(COMMAND, "claude", inputs.ownArtifact, arguments, new StringReader(RECORDS),
					new StringWriter(), parent.requests(), bounded());

				Path received = Path.of(value(parent.launches().getFirst(), "--workspace")).
					resolve("cat-draft/.cat/tests/AGENTS/shipment/workspace");
				requireThat(Files.readSymbolicLink(received.resolve("arrival-board.md")), "boardLink").
					isEqualTo(Path.of("../../arrival-board.md"));
				requireThat(Files.readString(received.resolve("arrival-board.md")), "board").isEqualTo("board");
				requireThat(Files.readSymbolicLink(received.resolve("guides")), "guidesLink").
					isEqualTo(Path.of("../../guides"));
				requireThat(Files.readString(received.resolve("guides/opening.md")), "opening").isEqualTo("opening");
			}
		}
	}

	/**
	 * Verifies that a draft's link to a project file that the draft does not hold, which the fork supplies, reaches the
	 * served session as a link with the same target even though the target is missing from the workspace, so that a
	 * revision of a draft that holds such a link starts instead of being refused before the session reads the feedback.
	 *
	 * @throws IOException if fixture or session I/O fails
	 */
	@Test(timeOut = 30_000)
	public void carriesLinkToForkFile() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "served-")).toRealPath();
			Inputs inputs = new Inputs(root);
			Path workspace = Files.createDirectories(
				inputs.workspace.resolve("cat-draft/.cat/tests/guidance/case/workspace"));
			Files.writeString(workspace.resolve("own.md"), "own");
			Files.createSymbolicLink(workspace.resolve("latest"), Path.of("own.md"));
			// Written for the prompt test's path in the project: five names up is the project's root there, so the
			// targets are in the fork, and the draft's root here, where they are missing
			Path link = Files.createSymbolicLink(workspace.resolve("reports"), Path.of("../../../../../reports"));
			Files.createSymbolicLink(workspace.resolve("desk.md"), Path.of("../../../../../docs/dispatch-desk.md"));
			requireThat(Files.exists(link), "targetExists").isFalse();
			requireThat(Files.exists(workspace.resolve("desk.md")), "deskExists").isFalse();
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), jvm.fileLocks(), 1))
			{
				List<String> arguments = new ArrayList<>(inputs.arguments());
				arguments.addAll(List.of("--workspace", inputs.workspace.toString()));
				SessionRequestForwarder.serve(COMMAND, "claude", inputs.ownArtifact, arguments, new StringReader(RECORDS),
					new StringWriter(), parent.requests(), bounded());

				Path received = Path.of(value(parent.launches().getFirst(), "--workspace")).
					resolve("cat-draft/.cat/tests/guidance/case/workspace");
				requireThat(Files.readSymbolicLink(received.resolve("reports")), "reportsLink").
					isEqualTo(Path.of("../../../../../reports"));
				requireThat(Files.readSymbolicLink(received.resolve("desk.md")), "deskLink").
					isEqualTo(Path.of("../../../../../docs/dispatch-desk.md"));
				requireThat(Files.readString(received.resolve("latest")), "latest").isEqualTo("own");
			}
		}
	}

	/**
	 * Supplies the symbolic links that a workspace may not hold, because their targets leave it as text, or through
	 * another link, or are absolute.
	 *
	 * @return the name of each case
	 */
	@DataProvider
	public Object[][] workspaceLinksThatLeaveIt()
	{
		return new Object[][]{{"outside"}, {"outside_after_missing_name"}, {"absolute"}, {"through_outside_link"}};
	}

	/**
	 * Verifies that a served session whose workspace holds a symbolic link that leads outside the workspace, as text,
	 * past a name that does not exist or through another link, or to an absolute path, is refused before the command
	 * publishes a request.
	 *
	 * @param kind the case
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "workspaceLinksThatLeaveIt", timeOut = 30_000)
	public void refusesWorkspaceLinkThatLeavesIt(String kind) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "served-")).toRealPath();
			Inputs inputs = new Inputs(root);
			Path link = inputs.workspace.resolve("link.md");
			switch (kind)
			{
				case "outside" -> Files.createSymbolicLink(link, Path.of("../evidence.txt"));
				case "outside_after_missing_name" -> Files.createSymbolicLink(link, Path.of("missing/../../evidence.txt"));
				case "absolute" -> Files.createSymbolicLink(link, inputs.workspace.resolve("notes.txt"));
				case "through_outside_link" ->
				{
					Files.createSymbolicLink(inputs.workspace.resolve("up"), Path.of(".."));
					Files.createSymbolicLink(link, Path.of("up/evidence.txt"));
				}
				default -> throw new AssertionError(kind);
			}
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), jvm.fileLocks(), 1))
			{
				List<String> arguments = new ArrayList<>(inputs.arguments());
				arguments.addAll(List.of("--workspace", inputs.workspace.toString()));
				try
				{
					SessionRequestForwarder.serve(COMMAND, "claude", inputs.ownArtifact, arguments,
						new StringReader(RECORDS), new StringWriter(), parent.requests(), bounded());
					throw new AssertionError("A workspace link that leaves the workspace was sent");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").contains("symbolic link");
				}
				requireThat(names(parent.requests()), "requests").isEmpty();
				requireThat(parent.launches(), "launches").isEmpty();
			}
		}
	}

	/**
	 * Verifies that a served session whose prompt names, by absolute path, a launcher of the CAT plugin in the
	 * requesting session's home, such as the contract tools that a contract author runs, is not denied reading that
	 * launcher, while the runner still denies it the rest of the requesting nested session, such as that session's
	 * login.
	 *
	 * @throws IOException if fixture or session I/O fails
	 */
	@Test(timeOut = 30_000)
	public void readsLauncherThatItsRequestNames() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "served-")).toRealPath();
			Inputs inputs = new Inputs(root);
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), jvm.fileLocks(), 1))
			{
				Path launcher = parent.plugin().resolve("bin/prompt-optimizer");
				Files.writeString(Files.createDirectories(launcher.getParent()).resolve(launcher.getFileName()),
					"launcher");
				Path login = parent.home().resolve(".claude/.credentials.json");
				Files.writeString(Files.createDirectories(login.getParent()).resolve(login.getFileName()), "login");
				String records = json.createObjectNode().put("type", "turn").
					put("prompt", "Run " + launcher + " contract-tools to check the draft.").toString() + """

					{"type":"complete"}
					""";

				SessionRequestForwarder.serve(COMMAND, "claude", inputs.ownArtifact, inputs.arguments(),
					new StringReader(records), new StringWriter(), parent.requests(), bounded());

				List<Path> denied = values(parent.launches().getFirst(), "--deny-read").stream().map(Path::of).toList();
				for (Path path : denied)
					requireThat(launcher.startsWith(path), "launcher.startsWith(" + path + ")").isFalse();
				requireThat(denied.stream().anyMatch(login::startsWith), "loginDenied").isTrue();
				requireThat(onlyResponse(parent).path("status").asString(), "status").isEqualTo("completed");
			}
		}
	}

	/**
	 * Supplies the session requests of a nested session whose runner refuses the next session, and whether the nested
	 * session first serves the one session it allows.
	 *
	 * @return the session requests of the nested session and whether one session is served first
	 */
	@DataProvider
	public Object[][] spentSessions()
	{
		return new Object[][]{{0, false}, {1, true}};
	}

	/**
	 * Verifies that a nested session that allows no session, or whose sessions are spent, refuses the next session with
	 * the message that names the caller's options that would allow it, before the command publishes a request or writes
	 * into its results folder.
	 *
	 * @param sessionRequests how many sessions the nested session allows
	 * @param serveFirst      indicates whether the nested session serves one session before
	 * @throws IOException if fixture or session I/O fails
	 */
	@Test(dataProvider = "spentSessions", timeOut = 30_000)
	public void refusesBeforeAnyChange(int sessionRequests, boolean serveFirst) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "served-")).toRealPath();
			Inputs inputs = new Inputs(root);
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), jvm.fileLocks(),
				sessionRequests))
			{
				if (serveFirst)
				{
					SessionRequestForwarder.serve(COMMAND, "claude", inputs.ownArtifact, inputs.arguments(),
						new StringReader(RECORDS), new StringWriter(), parent.requests(), bounded());
				}
				List<String> requestsBefore = names(parent.requests());
				List<String> resultsBefore = names(inputs.resultsFolder);
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
				requireThat(names(inputs.resultsFolder), "results").isEqualTo(resultsBefore);
			}
		}
	}

	/**
	 * Verifies that a session that the nested session's runner refuses because the nested session's limit ran out after
	 * the command checked it is refused with the same message, so that a campaign pauses instead of counting a failure.
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
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), jvm.fileLocks(), 1))
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
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), jvm.fileLocks(), 1))
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
			{List.of("--conversation", "folder"), List.of()}};
	}

	/**
	 * Verifies that a served session takes its records from the command and keeps its nested session in the command's
	 * results folder, so a launch that names other records is refused before it publishes a request.
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
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), jvm.fileLocks(), 1))
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
	 * Serves a session that the nested session must refuse.
	 *
	 * @param parent the nested session
	 * @param inputs the session's inputs
	 * @return the refusal
	 * @throws IOException if the session fails for another reason
	 */
	private static LaunchRefusedException refusal(RequestingNestedSession parent, Inputs inputs) throws IOException
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
		throw new AssertionError("The nested session served a session that it does not allow");
	}

	/**
	 * Returns the response of the nested session's only request.
	 *
	 * @param parent the nested session
	 * @return the response
	 * @throws IOException if the nested session has no single response
	 */
	private JsonNode onlyResponse(RequestingNestedSession parent) throws IOException
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
	 * Returns the values of a repeatable option, in order.
	 *
	 * @param arguments the options
	 * @param name      the option's name
	 * @return its values
	 */
	private static List<String> values(List<String> arguments, String name)
	{
		List<String> values = new ArrayList<>();
		for (int i = 0; i < arguments.size() - 1; ++i)
		{
			if (arguments.get(i).equals(name))
				values.add(arguments.get(i + 1));
		}
		return values;
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
		final Path resultsFolder;
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
			resultsFolder = root.resolve("results");
			workspace = Files.createDirectories(root.resolve("workspace"));
			Files.writeString(workspace.resolve("notes.txt"), "notes");
			evidence = Files.writeString(root.resolve("evidence.txt"), "evidence");
			candidate = Files.createDirectories(root.resolve("candidate"));
			Files.writeString(candidate.resolve("marker"), "candidate");
			ownArtifact = Files.createDirectories(root.resolve("own"));
		}

		/**
		 * Returns the options of a session that names only its project, results folder, model and effort.
		 *
		 * @return the options
		 */
		List<String> arguments()
		{
			return List.of("--project", project.toString(), "--results", resultsFolder.toString(), "--model",
				"model-a", "--effort", "effort-a");
		}
	}
}
