package com.holdmyspot.cat.common.runner.contract.test;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.SessionCommandLimits;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * The limits that every runner must give the commands of a session, as {@link SessionCommandLimits} defines them. A
 * test class of each runner extends this class and starts a session of a test-owned run the way its runner does,
 * without starting a harness, and returns the environment that the session's commands receive and the paths that they
 * may not read. Each test fails if the runner drops a limit.
 */
public abstract class SessionCommandLimitsContract
{
	/**
	 * The value of each credential variable in the user's environment.
	 */
	private static final String CREDENTIAL = "credential";

	/**
	 * Creates the contract.
	 */
	protected SessionCommandLimitsContract()
	{
	}

	/**
	 * The user and run that a session starts from: a user's home holding every harness's login, configuration and an
	 * installed plugin, and a run root holding the session's run and another run.
	 *
	 * @param userEnvironment the environment of the process that starts the runner, which names the user's home, a
	 *                        separate Codex home, and every credential variable
	 * @param userHome        the user's home directory
	 * @param manager         the run manager of the run root
	 * @param run             the session's run, whose project is snapshotted
	 * @param otherRun        another run under the same run root
	 * @param artifact        the harness artifact that the session's runner uses
	 * @param deniedReads     additional directories that the caller denies reading
	 */
	public record Fixture(Map<String, String> userEnvironment, Path userHome, HarnessRunManager manager,
		HarnessRun run, HarnessRun otherRun, Path artifact, List<Path> deniedReads)
	{
		/**
		 * Returns the run's isolated home.
		 *
		 * @return the home
		 */
		public Path runHome()
		{
			return run.directory().resolve("home");
		}
	}

	/**
	 * What a runner gives the commands of a session.
	 *
	 * @param commandEnvironment the environment that the session's commands receive
	 * @param deniedReads        the files and directories that the session's commands may not read
	 * @param writable           the files and directories outside the session's project that the session's commands
	 *                           may write
	 * @param readable           the files and directories that the sandbox lets the session's commands read, without
	 *                           writing them, even inside a denied directory
	 */
	public record Limits(Map<String, String> commandEnvironment, List<Path> deniedReads, List<Path> writable,
		List<Path> readable)
	{
		/**
		 * Creates the limits.
		 *
		 * @param commandEnvironment the environment that the session's commands receive
		 * @param deniedReads        the files and directories that the session's commands may not read
		 * @param writable           the files and directories outside the session's project that the session's
		 *                           commands may write
		 * @param readable           the files and directories that the sandbox lets the session's commands read,
		 *                           without writing them, even inside a denied directory
		 */
		public Limits
		{
			commandEnvironment = Map.copyOf(commandEnvironment);
			deniedReads = List.copyOf(deniedReads);
			writable = List.copyOf(writable);
			readable = List.copyOf(readable);
		}
	}

	/**
	 * Prepares a session of the fixture's run as the runner under test does, without starting a harness, and returns
	 * what its commands receive.
	 *
	 * @param fixture         the user and run
	 * @param sessionRequests how many sessions the session may request, as {@code --session-requests} sets it
	 * @return the limits of the session's commands
	 * @throws IOException if the runner's configuration cannot be written or read
	 */
	protected abstract Limits limits(Fixture fixture, int sessionRequests) throws IOException;

	/**
	 * Returns the harness files in the run's home that the runner under test leaves readable to the session's
	 * commands, because a tool that the session runs reads them. None of them may hold a login.
	 *
	 * @param fixture the user and run
	 * @return the files; empty unless a subclass returns others
	 */
	protected List<Path> readableHarnessFiles(Fixture fixture)
	{
		return List.of();
	}

	/**
	 * Verifies that the session's commands run with the run's isolated home as their home directory, in every variable
	 * that names it on the platform.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void commandsRunInRunHome() throws IOException
	{
		// Requirement: R-I8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = fixture(cleanup);
			Limits limits = limits(fixture, 0);
			for (Map.Entry<String, String> variable : SessionCommandLimits.homeEnvironment(fixture.runHome(),
				Platform.current()).entrySet())
			{
				requireThat(limits.commandEnvironment().get(variable.getKey()), variable.getKey()).
					isEqualTo(variable.getValue());
			}
		}
	}

	/**
	 * Verifies that the session's commands receive none of the harness credential variables that the user's
	 * environment sets.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void commandsReceiveNoCredential() throws IOException
	{
		// Requirement: R-I8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Limits limits = limits(fixture(cleanup), 0);
			Set<String> received = new LinkedHashSet<>(limits.commandEnvironment().keySet());
			received.retainAll(SessionCommandLimits.CREDENTIAL_VARIABLES);
			requireThat(received, "received").isEmpty();
		}
	}

	/**
	 * Verifies that the session's commands may not read any harness's login or configuration files, in the user's
	 * home, in the Codex home that the user names, or in the run's home, while the rest of the user's harness
	 * directories, where installed plugins live, stay readable.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void commandsCannotReadHarnessFiles() throws IOException
	{
		// Requirement: R-I8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = fixture(cleanup);
			Limits limits = limits(fixture, 0);
			Platform platform = Platform.current();
			// A harness that a command starts finds its files through the command's environment, or else through the
			// user's environment if the command names the user's directories
			Map<String, String> commandEnvironment = new HashMap<>(limits.commandEnvironment());
			commandEnvironment.putAll(SessionCommandLimits.homeEnvironment(fixture.runHome(), platform));
			Set<Path> expected = new LinkedHashSet<>();
			for (Path file : SessionCommandLimits.harnessFiles(fixture.userEnvironment(), platform))
			{
				if (Files.exists(file))
					expected.add(file.toRealPath());
			}
			// The fixture gives the user every login and configuration file of both harnesses
			requireThat(expected.size(), "expected.size()").isEqualTo(8);
			for (Path file : SessionCommandLimits.harnessFiles(commandEnvironment, platform))
			{
				if (Files.exists(file))
					expected.add(file.toRealPath());
			}
			expected.removeAll(realPaths(readableHarnessFiles(fixture)));

			Set<Path> denied = realPaths(limits.deniedReads());
			for (Path file : expected)
				requireThat(denied, "deniedReads").contains(file);
			for (Path directory : List.of(fixture.userHome().resolve(".claude"), fixture.userHome().resolve(".codex"),
				fixture.userHome().resolve(".claude/plugins/cache/example")))
			{
				for (Path path : denied)
				{
					requireThat(directory.toRealPath().startsWith(path), "coversDirectory").
						withContext(directory, "directory").withContext(path, "deniedRead").isFalse();
				}
			}
		}
	}

	/**
	 * Verifies that the session's commands may not read another run under the run root.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void commandsCannotReadOtherRuns() throws IOException
	{
		// Requirement: R-I8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = fixture(cleanup);
			Limits limits = limits(fixture, 0);
			Path other = fixture.otherRun().directory().toRealPath();
			requireThat(realPaths(limits.deniedReads()).stream().anyMatch(other::startsWith), "otherRunDenied").
				withContext(limits.deniedReads(), "deniedReads").isTrue();
		}
	}

	/**
	 * Verifies that a session that may request sessions can write the run's requests directory, and read but not write
	 * its responses directory, that its commands are told where requests go and which launcher makes one, and that they
	 * cannot read the directory that holds the runs of the sessions it requests.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void commandsCanRequestSessions() throws IOException
	{
		// Requirement: R-I9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = fixture(cleanup);
			Limits limits = limits(fixture, 2);
			Path requests = fixture.run().directory().resolve("requests");
			Path responses = fixture.run().directory().resolve("responses");
			String launcher = "request-session";
			if (Platform.current().isWindows())
				launcher += ".cmd";

			requireThat(requests, "requests").isDirectory();
			requireThat(responses, "responses").isDirectory();
			requireThat(limits.commandEnvironment().get("CAT_SESSION_REQUESTS"), "CAT_SESSION_REQUESTS").
				isEqualTo(requests.toString());
			requireThat(limits.commandEnvironment().get("CAT_REQUEST_SESSION"), "CAT_REQUEST_SESSION").
				isEqualTo(fixture.artifact().resolve("bin").resolve(launcher).toString());
			Set<Path> writable = realPaths(limits.writable());
			requireThat(writable, "writable").contains(requests.toRealPath());
			for (Path path : writable)
			{
				requireThat(responses.toRealPath().startsWith(path), "responsesWritable").withContext(path, "writable").
					isFalse();
			}
			requireThat(readable(limits, requests), "requestsReadable").isTrue();
			requireThat(readable(limits, responses), "responsesReadable").isTrue();
			// The runs of requested sessions hold their logins while they run
			Path requestedRuns = fixture.run().directory().resolve("requested-runs");
			requireThat(requestedRuns, "requestedRuns").isDirectory();
			requireThat(readable(limits, requestedRuns), "requestedRunsReadable").isFalse();
			requireThat(requestedRuns.resolve("1"), "firstRequestedRunRoot").isDirectory();
			requireThat(requestedRuns.resolve("2"), "secondRequestedRunRoot").isDirectory();
		}
	}

	/**
	 * Verifies that a requested session can read its own project while the parent's run root denies parent and sibling
	 * runs, including credentials created after the sibling run roots were allocated.
	 *
	 * @throws IOException if fixture or configuration I/O fails
	 */
	@Test
	public void childCannotReadParentOrSibling() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-I9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture parent = fixture(cleanup);
			limits(parent, 2);
			Path requested = parent.run().directory().resolve("requested-runs");
			HarnessRunManager manager = new HarnessRunManager(fileLocks, requested.resolve("1"), Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun childRun = manager.create("harness");
			manager.snapshotProject(childRun);
			Fixture child = new Fixture(parent.userEnvironment(), parent.userHome(), manager, childRun,
				parent.run(), parent.artifact(), List.of(parent.manager().root()));
			write(requested.resolve("2/future/home/login"));

			Limits childLimits = limits(child, 0);

			requireThat(readable(childLimits, childRun.directory().resolve("project")), "ownProject").isTrue();
			requireThat(readable(childLimits, parent.runHome()), "parentHome").isFalse();
			requireThat(readable(childLimits, requested.resolve("2/future/home/login")), "siblingLogin").isFalse();
		}
	}

	/**
	 * Verifies that a session that may request no session gets neither the requests nor the responses directory, nor
	 * the variables that name them.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void noRequestsByDefault() throws IOException
	{
		// Requirement: R-I9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = fixture(cleanup);
			Limits limits = limits(fixture, 0);

			requireThat(Files.exists(fixture.run().directory().resolve("requests")), "requestsExist").isFalse();
			requireThat(Files.exists(fixture.run().directory().resolve("responses")), "responsesExist").isFalse();
			requireThat(limits.commandEnvironment().keySet(), "commandVariables").doesNotContain("CAT_SESSION_REQUESTS").
				doesNotContain("CAT_REQUEST_SESSION");
		}
	}

	/**
	 * Indicates whether the session's commands may read a directory: no denied path covers it, or the sandbox grants
	 * it, as writable or readable, by a path deeper than every denied path that covers it.
	 *
	 * @param limits    the limits of the session's commands
	 * @param directory an existing directory
	 * @return {@code true} if the commands may read it
	 * @throws IOException if a real path cannot be read
	 */
	private static boolean readable(Limits limits, Path directory) throws IOException
	{
		Path real = directory.toRealPath();
		int deepestDenial = -1;
		for (Path denied : realPaths(limits.deniedReads()))
		{
			if (real.startsWith(denied))
				deepestDenial = Math.max(deepestDenial, denied.getNameCount());
		}
		if (deepestDenial < 0)
			return true;
		Set<Path> grants = realPaths(limits.writable());
		grants.addAll(realPaths(limits.readable()));
		for (Path grant : grants)
		{
			if (real.startsWith(grant) && grant.getNameCount() > deepestDenial)
				return true;
		}
		return false;
	}

	/**
	 * Creates a user with every harness's login and configuration and an installed plugin, and a run root with the
	 * session's run and another run.
	 *
	 * @param cleanup removes the fixture's directories
	 * @return the fixture
	 * @throws IOException if the fixture cannot be created
	 */
	private Fixture fixture(TemporaryDirectories cleanup) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-session-command-limits-")).toRealPath();
			Path userHome = Files.createDirectories(root.resolve("user"));
			Path codexHome = Files.createDirectories(root.resolve("codex-home"));
			for (String file : List.of(".claude/.credentials.json", ".claude/settings.json", ".claude/settings.local.json",
				".claude.json", ".codex/auth.json", ".codex/config.toml", ".claude/plugins/cache/example/plugin.json"))
			{
				write(userHome.resolve(file));
			}
			write(codexHome.resolve("auth.json"));
			write(codexHome.resolve("config.toml"));
			Files.createDirectories(userHome.resolve(".npm"));
			Files.createDirectories(userHome.resolve(".m2"));

			Map<String, String> userEnvironment = new HashMap<>();
			userEnvironment.put("PATH", "");
			userEnvironment.put(Platform.current().homeVariable(), userHome.toString());
			userEnvironment.putAll(SessionCommandLimits.homeEnvironment(userHome, Platform.current()));
			userEnvironment.put("CODEX_HOME", codexHome.toString());
			for (String variable : SessionCommandLimits.CREDENTIAL_VARIABLES)
				userEnvironment.put(variable, CREDENTIAL);

			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(),
				root.resolve("runs"), Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			manager.snapshotProject(run);
			Path runHome = run.directory().resolve("home");
			// The logins and configuration that a runner may have put in the run's home for its harness
			for (String file : List.of(".claude/.credentials.json", ".claude/settings.json", ".claude.json",
				".codex/auth.json", "auth.json", "config.toml"))
			{
				write(runHome.resolve(file));
			}
			HarnessRun otherRun = manager.create("harness");
			write(otherRun.directory().resolve("project/notes.txt"));
			Path artifact = Files.createDirectories(root.resolve("artifact"));
			return new Fixture(Map.copyOf(userEnvironment), userHome, manager, run, otherRun, artifact, List.of());
		}
	}

	/**
	 * Writes a placeholder file and the directories above it. TOML files receive an empty document; other files
	 * receive a JSON object.
	 *
	 * @param file the file
	 * @throws IOException if the file cannot be written
	 */
	private static void write(Path file) throws IOException
	{
		Files.createDirectories(file.getParent());
		String content = "{}";
		if (file.getFileName().toString().endsWith(".toml"))
			content = "";
		Files.writeString(file, content);
	}

	/**
	 * Returns the real paths of paths that exist and the normalized absolute paths of the others.
	 *
	 * @param paths the paths
	 * @return the paths, each once
	 * @throws IOException if a real path cannot be read
	 */
	private static Set<Path> realPaths(List<Path> paths) throws IOException
	{
		Set<Path> result = new LinkedHashSet<>();
		for (Path path : paths)
		{
			if (Files.exists(path))
				result.add(path.toRealPath());
			else
				result.add(path.toAbsolutePath().normalize());
		}
		return result;
	}
}
