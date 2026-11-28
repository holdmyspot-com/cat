package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import com.holdmyspot.cat.codex.optimizer.CodexHookProcess;
import com.holdmyspot.cat.codex.runner.TomlStrings;
import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link CodexHookProcess}.
 */
public final class CodexHookProcessTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * The variable that names a POSIX hook's home folder, which Codex hooks also receive on Windows.
	 */
	private static final String HOME = Platform.LINUX_X64.homeVariable();
	private static final List<String> HOOK = List.of("hook-program", "--flag", "a \"quoted\" value");

	/**
	 * Creates the test.
	 */
	public CodexHookProcessTest()
	{
	}

	/**
	 * Returns every platform family on which CAT confines a startup hook.
	 *
	 * @return the platforms
	 */
	@DataProvider
	public static Object[][] platforms()
	{
		return new Object[][]
		{
			{Platform.LINUX_X64},
			{Platform.MACOS_ARM64},
			{Platform.WINDOWS_X64}
		};
	}

	/**
	 * Verifies that on native Windows the hook starts under the sandbox of the owner's own Codex executable, in the
	 * Windows implementation that the owner's settings select, with its variables set and removed by Codex, instead of
	 * being refused. Each argument that CAT adds is quoted the way that Windows programs split their command line,
	 * since Java would otherwise pass a double quote inside it unescaped and Codex would receive the argument without it.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the owner could replace the executable
	 */
	@Test
	public void windowsHookRunsUnderOwnersSelection() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			ProcessBuilder builder = fixture.builder();
			builder.environment().put("PATH", "C:\\Tools;C:\\Windows\\System32");
			builder.environment().put(HOME, "C:\\Users\\owner");
			builder.environment().remove("CODEX_HOME");
			Map<String, String> selection = Map.of("windows.sandbox", "elevated", "windows.allow_mxc", "true");

			CodexHookProcess.prepare(builder, fixture.scratch, Optional.empty(),
				fixture.sandbox(Platform.WINDOWS_X64, selection), fixture.policy(List.of()), JSON);

			List<String> command = builder.command();
			requireThat(command.getFirst(), "executable").isEqualTo(fixture.codex.toString());
			requireThat(command.subList(1, 3), "sandbox").isEqualTo(
				List.of(CommandLineArguments.quote("sandbox"), CommandLineArguments.quote("--sandbox-state-json")));
			requireThat(JSON.readTree(received(Platform.WINDOWS_X64, command).get(3)).isObject(), "state").isTrue();
			List<String> expected = new ArrayList<>(List.of(
				"-c", "project_root_markers=[]",
				"-c", "projects={\"" + TomlStrings.escape(fixture.workspace.toString()) +
					"\"={trust_level=\"untrusted\"}}",
				"-c", "features.network_proxy=false",
				"-c", "shell_environment_policy.inherit=\"all\"",
				"-c", "windows.allow_mxc=true",
				"-c", "windows.sandbox=\"elevated\"",
				"-c", "shell_environment_policy.set.HOME=\"C:\\\\Users\\\\owner\"",
				"-c", "shell_environment_policy.set.PATH=\"C:\\\\Tools;C:\\\\Windows\\\\System32\"",
				"-c", "shell_environment_policy.exclude=[\"CODEX_HOME\"]",
				"--")).stream().map(CommandLineArguments::quote).collect(Collectors.toList());
			// The hook's own arguments are already as Java starts the hook, and Codex starts it with the same words
			expected.addAll(HOOK);
			requireThat(command.subList(4, command.size()), "command").isEqualTo(expected);
			// Codex finds its Windows helpers beside its own executable, so the search path is left to the hook
			requireThat(builder.environment().get("PATH"), "PATH").isEqualTo("C:\\Tools;C:\\Windows\\System32");
			Path sandboxHome = Path.of(builder.environment().get("CODEX_HOME"));
			requireThat(sandboxHome.getParent(), "sandboxHome").isEqualTo(fixture.scratch);
			requireThat(builder.environment().get(HOME), HOME).isEqualTo(sandboxHome.toString());
		}
	}

	/**
	 * Verifies that on every platform Codex itself gives the hook its own variables, so that no other program runs
	 * inside the sandbox before the hook.
	 *
	 * @param platform the host platform
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the owner could replace the executable
	 */
	@Test(dataProvider = "platforms")
	public void hookGetsItsVariablesFromCodex(Platform platform) throws IOException, PolicyRefusal
	{
		requireSearchPathOnHost(platform);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			ProcessBuilder builder = fixture.builder();
			builder.environment().put(HOME, "/home/owner");
			builder.environment().put("CODEX_HOME", "/home/owner/.codex");
			builder.environment().remove("PATH");

			CodexHookProcess.prepare(builder, fixture.scratch, Optional.empty(), fixture.sandbox(platform, Map.of()),
				fixture.policy(List.of()), JSON);

			List<String> command = received(platform, builder.command());
			int separator = command.lastIndexOf("--");
			requireThat(command.subList(separator + 1, command.size()), "hook").isEqualTo(HOOK);
			requireThat(command.subList(4, separator), "options").containsAll(List.of(
				"shell_environment_policy.set.HOME=\"/home/owner\"",
				"shell_environment_policy.set.CODEX_HOME=\"/home/owner/.codex\"",
				"shell_environment_policy.exclude=[\"PATH\"]"));
			requireThat(command.stream().filter(argument -> argument.endsWith("env")).toList(), "env").isEmpty();
		}
	}

	/**
	 * Verifies that on Linux and macOS the Codex launcher keeps the search path from which it takes {@code bwrap},
	 * which the owner cannot replace.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the owner could replace the executable
	 */
	@Test
	public void linuxLauncherSearchesSystemFolders() throws IOException, PolicyRefusal
	{
		requireSearchPathOnHost(Platform.LINUX_X64);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			ProcessBuilder builder = fixture.builder();
			builder.environment().put("PATH", "/opt/tools/bin:/usr/bin");

			CodexHookProcess.prepare(builder, fixture.scratch, Optional.empty(),
				fixture.sandbox(Platform.LINUX_X64, Map.of()), fixture.policy(List.of()), JSON);

			requireThat(builder.environment().get("PATH"), "PATH").isEqualTo("/usr/bin:/bin");
			requireThat(builder.command(), "command").contains("shell_environment_policy.set.PATH=" +
				"\"/opt/tools/bin:/usr/bin\"");
		}
	}

	/**
	 * Verifies that a hook that Windows runs under {@code unelevated}, which cannot deny a read and refuses a profile
	 * that denies one, gets no denied reads.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the owner could replace the executable
	 */
	@Test
	public void unelevatedHookHasNoDeniedReads() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			ProcessBuilder builder = fixture.builder();

			CodexHookProcess.prepare(builder, fixture.scratch, Optional.empty(),
				fixture.sandbox(Platform.WINDOWS_X64, Map.of("windows.sandbox", "unelevated")),
				fixture.policy(List.of(fixture.denied)), JSON);

			requireThat(accesses(builder), "accesses").doesNotContain("deny");
		}
	}

	/**
	 * Verifies that a hook that Windows runs under another implementation, and a hook on Linux, keep the owner's denied
	 * reads.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the owner could replace the executable
	 */
	@Test
	public void elevatedAndLinuxHooksKeepDeniedReads() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			List<CodexHookProcess.Sandbox> sandboxes = new ArrayList<>();
			sandboxes.add(fixture.sandbox(Platform.WINDOWS_X64, Map.of("windows.sandbox", "elevated")));
			// The Linux launcher's search path exists only on a POSIX host
			if (!Platform.current().isWindows())
				sandboxes.add(fixture.sandbox(Platform.LINUX_X64, Map.of("windows.sandbox", "unelevated")));
			for (CodexHookProcess.Sandbox sandbox : sandboxes)
			{
				ProcessBuilder builder = fixture.builder();
				CodexHookProcess.prepare(builder, fixture.scratch, Optional.empty(), sandbox,
					fixture.policy(List.of(fixture.denied)), JSON);

				requireThat(accesses(builder), "accesses").contains("deny");
			}
		}
	}

	/**
	 * Verifies that on native Windows the hook is refused when the owner's commands could replace the helpers that
	 * Codex takes from its executable's folder.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesWritableWindowsCodexFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			ParentPolicy policy = new ParentPolicy(true, List.of(fixture.scratch, fixture.codex.getParent()), List.of(),
				List.of(), List.of(), HostAccess.NONE, false, List.of());

			expectThrows(PolicyRefusal.class, () -> CodexHookProcess.prepare(fixture.builder(), fixture.scratch,
				Optional.empty(), fixture.sandbox(Platform.WINDOWS_X64, Map.of()), policy, JSON));
		}
	}

	/**
	 * Verifies that the owner's Codex executable is the program of the owner's own process.
	 *
	 * @throws IOException if the process cannot be read
	 */
	@Test
	public void ownerExecutableIsTheOwnersProcess() throws IOException
	{
		ProcessIdentity owner = new ProcessIdentity(42, "started");
		Path executable = CodexHookProcess.ownerExecutable(processId -> Optional.of(new ProcessDescription(
			processId, Optional.of("started"), Optional.of("C:\\codex\\codex.exe"), Optional.empty(),
			Optional.empty())), owner);

		requireThat(executable, "executable").isEqualTo(Path.of("C:\\codex\\codex.exe"));
	}

	/**
	 * Verifies that a process that took the owner's identifier after the owner ended does not supply the executable.
	 */
	@Test
	public void refusesReusedOwnerProcessId()
	{
		ProcessIdentity owner = new ProcessIdentity(42, "started");
		IOException failure = expectThrows(IOException.class, () -> CodexHookProcess.ownerExecutable(
			processId -> Optional.of(new ProcessDescription(processId, Optional.of("later"),
				Optional.of("C:\\other.exe"), Optional.empty(), Optional.empty())), owner));

		requireThat(failure.getMessage(), "message").contains("Codex");
	}

	/**
	 * Skips a case whose platform searches {@code /usr/bin:/bin}, which a Windows host lacks.
	 *
	 * @param platform the platform that the case confines a hook on
	 */
	private static void requireSearchPathOnHost(Platform platform)
	{
		if (!platform.isWindows() && Platform.current().isWindows())
			throw new SkipException("The Linux and macOS launcher's search path /usr/bin:/bin exists only on a " +
				"POSIX host");
	}

	/**
	 * Returns the words that Codex receives from a prepared command line: on Windows, each argument that CAT adds,
	 * up to and including the one that ends Codex's options, is split the way that Windows programs split their
	 * command line; the hook's own arguments and every argument on other platforms are received as they are.
	 *
	 * @param platform the platform that the command starts on
	 * @param command  the prepared command
	 * @return the received words
	 */
	private static List<String> received(Platform platform, List<String> command)
	{
		if (!platform.isWindows())
			return command;
		int separator = command.indexOf(CommandLineArguments.quote("--"));
		requireThat(separator, "separator").isNotNegative();
		List<String> words = new ArrayList<>(command);
		for (int i = 1; i <= separator; ++i)
			words.set(i, unquoted(command.get(i)));
		return words;
	}

	/**
	 * Splits one quoted argument the way that Windows programs split their command line: the outer quotes group the
	 * word, backslashes before a quote escape it in pairs, and other backslashes are plain characters.
	 *
	 * @param argument a quoted argument
	 * @return the word that the program receives
	 */
	private static String unquoted(String argument)
	{
		requireThat(argument, "argument").startsWith("\"").endsWith("\"");
		String inner = argument.substring(1, argument.length() - 1);
		StringBuilder word = new StringBuilder();
		int backslashes = 0;
		for (int i = 0; i < inner.length(); ++i)
		{
			char c = inner.charAt(i);
			if (c == '\\')
			{
				++backslashes;
				continue;
			}
			if (c == '"')
			{
				requireThat(backslashes % 2, "unescapedQuote").isEqualTo(1);
				word.repeat('\\', backslashes / 2);
			}
			else
				word.repeat('\\', backslashes);
			word.append(c);
			backslashes = 0;
		}
		requireThat(backslashes % 2, "trailingBackslashes").isEqualTo(0);
		return word.repeat('\\', backslashes / 2).toString();
	}

	/**
	 * Returns the access mode of every filesystem entry of the hook's sandbox state.
	 *
	 * @param builder the prepared hook builder
	 * @return the access modes
	 */
	private static List<String> accesses(ProcessBuilder builder)
	{
		List<String> command = builder.command();
		Platform platform = Platform.LINUX_X64;
		if (command.contains(CommandLineArguments.quote("--")))
			platform = Platform.WINDOWS_X64;
		JsonNode state = JSON.readTree(received(platform, command).get(3));
		List<String> accesses = new ArrayList<>();
		for (JsonNode entry : state.path("permissionProfile").path("file_system").path("entries"))
			accesses.add(entry.path("access").asString());
		return accesses;
	}

	/**
	 * The comparison folder, its workspace, a Codex executable outside the owner's writable folders and a folder that
	 * the owner may be denied.
	 */
	private static final class Fixture
	{
		private final Path scratch;
		private final Path workspace;
		private final Path codex;
		private final Path denied;
		private final Path system;

		/**
		 * Creates the folders and the executable.
		 *
		 * @param cleanup the scope that removes them
		 * @throws IOException if a file cannot be created
		 */
		Fixture(TemporaryDirectories cleanup) throws IOException
		{
			Path root = cleanup.add(Files.createTempDirectory("codex-hook-process-")).toRealPath();
			scratch = Files.createDirectories(root.resolve("comparison"));
			workspace = Files.createDirectories(scratch.resolve("production"));
			codex = Files.createFile(Files.createDirectories(root.resolve("codex")).resolve("codex.exe"));
			denied = Files.createDirectories(root.resolve("denied"));
			system = Files.createDirectories(root.resolve("system"));
		}

		/**
		 * Returns a builder for the hook in the workspace.
		 *
		 * @return the builder
		 */
		ProcessBuilder builder()
		{
			return new ProcessBuilder(HOOK).directory(workspace.toFile());
		}

		/**
		 * Returns an owner policy that may write the comparison folder.
		 *
		 * @param readDenials the paths that the owner may not read
		 * @return the policy
		 */
		ParentPolicy policy(List<Path> readDenials)
		{
			return new ParentPolicy(true, List.of(scratch), List.of(), readDenials, List.of(), HostAccess.NONE, false,
				List.of());
		}

		/**
		 * Returns the sandbox that starts from the fixture's executable.
		 *
		 * @param platform  the host platform
		 * @param selection the owner's Windows sandbox selection
		 * @return the sandbox
		 */
		CodexHookProcess.Sandbox sandbox(Platform platform, Map<String, String> selection)
		{
			return new CodexHookProcess.Sandbox(platform, codex, selection, CodexSystemFolder.at(system));
		}
	}
}
