package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import com.holdmyspot.cat.codex.runner.CodexSessionSandbox;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexSessionSandbox}.
 */
public final class CodexSessionSandboxTest
{
	/**
	 * Returns the Codex configurations that select a Windows sandbox implementation: the configuration that the
	 * session takes its implementation from, the machine-wide configuration under it and the machine-wide
	 * requirements, each empty if the file does not exist.
	 *
	 * @return each configuration
	 */
	@DataProvider
	public Object[][] selections()
	{
		return new Object[][]
			{
				{"[windows]\nsandbox = \"elevated\"\n", "", ""},
				{"[windows]\nsandbox = \"unelevated\"\n", "", ""},
				// The machine-wide configuration applies under a session that selects nothing
				{"", "[windows]\nsandbox = \"elevated\"\n", ""},
				{"", "[windows]\nsandbox = \"unelevated\"\n", ""},
				// The session's own selection applies over the machine's
				{"[windows]\nsandbox = \"unelevated\"\n", "[windows]\nsandbox = \"mxc\"\n", ""},
				// With nothing configured, Codex runs an implementation that the requirements list
				{"", "", "[windows]\nallowed_sandbox_implementations = [\"elevated\", \"unelevated\"]\n"},
				{"", "", "[windows]\nallowed_sandbox_implementations = [\"unelevated\"]\n"},
				// Codex replaces an implementation that the requirements leave out with one that they list
				{"[windows]\nsandbox = \"elevated\"\n", "",
					"[windows]\nallowed_sandbox_implementations = [\"unelevated\"]\n"},
				{"[windows]\nsandbox = \"mxc\"\n", "", ""},
				{"", "[windows]\nsandbox = \"mxc\"\n", ""},
				{"[windows]\nsandbox = \"elevated\"\n", "", "[windows]\nrequire_mxc = true\n"},
				{"[windows]\nsandbox = \"mxc\"\n", "",
					"[windows]\nallowed_sandbox_implementations = [\"elevated\", \"unelevated\"]\n"},
				{"", "", "[windows]\nrequire_mxc = true\n"}
			};
	}

	/**
	 * Verifies that CAT starts a session whose effective configuration selects any Windows sandbox implementation,
	 * {@code elevated}, {@code unelevated} or {@code mxc}, since each counts as a sandbox.
	 *
	 * @param session      the configuration that the session takes its implementation from
	 * @param machine      the machine-wide configuration
	 * @param requirements the machine-wide requirements
	 * @throws IOException if fixture I/O fails, or the session is refused
	 */
	@Test(dataProvider = "selections")
	public void acceptsEveryImplementation(String session, String machine, String requirements)
		throws IOException
	{
		// Requirement: R-I45
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-windows-sandbox-"));
			Path configuration = layers(root, session, machine, requirements);

			CodexSessionSandbox.requireSelectedImplementation(configuration, CodexSystemFolder.at(root.resolve("system")));
		}
	}

	/**
	 * Returns the Codex configurations that select no Windows sandbox implementation, in the order that
	 * {@link #selections()} gives them.
	 *
	 * @return each configuration
	 */
	@DataProvider
	public Object[][] noSelections()
	{
		return new Object[][]
			{
				{"", "", ""},
				{"model = \"parent-model\"\n", "[windows]\nsandbox_private_desktop = false\n", ""},
				{"", "", "[windows]\nallowed_sandbox_implementations = []\n"}
			};
	}

	/**
	 * Verifies that CAT refuses a session whose effective configuration selects no Windows sandbox implementation,
	 * under which Codex rejects every command, and that the refusal names the setting to add.
	 *
	 * @param session      the configuration that the session takes its implementation from
	 * @param machine      the machine-wide configuration
	 * @param requirements the machine-wide requirements
	 * @throws IOException if fixture I/O fails, or the session is refused
	 */
	@Test(dataProvider = "noSelections", expectedExceptions = SessionRefusedException.class,
		expectedExceptionsMessageRegExp = "CAT does not start the session: .*\\[windows\\] sandbox.*")
	public void refusesNoImplementation(String session, String machine, String requirements) throws IOException
	{
		// Requirement: R-I44
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-windows-sandbox-"));
			Path configuration = layers(root, session, machine, requirements);

			CodexSessionSandbox.requireSelectedImplementation(configuration, CodexSystemFolder.at(root.resolve("system")));
		}
	}

	/**
	 * Returns the configurations under which Codex runs a child in its {@code unelevated} implementation: the
	 * parent's Windows selection that the child's configuration carries, the machine-wide configuration under it, the
	 * machine-wide managed configuration over it and the machine-wide requirements, each empty if absent.
	 *
	 * @return each configuration
	 */
	@DataProvider
	public Object[][] unelevatedChildren()
	{
		String elevated = "[windows]\nsandbox = \"elevated\"\n";
		String unelevated = "[windows]\nsandbox = \"unelevated\"\n";
		return new Object[][]
			{
				{Map.of("windows.sandbox", "unelevated"), "", "", ""},
				// The machine-wide configuration applies under a child that selects nothing
				{Map.of(), unelevated, "", ""},
				// The child's selection applies over the machine's
				{Map.of("windows.sandbox", "unelevated"), elevated, "", ""},
				// The managed configuration applies over the child's selection
				{Map.of("windows.sandbox", "elevated"), "", unelevated, ""}
			};
	}

	/**
	 * Verifies that a child that Codex runs in its {@code unelevated} implementation, which cannot deny reads and
	 * refuses a configuration that denies any, is denied none, on both Windows architectures.
	 *
	 * @param selection    the parent's Windows selection, by dotted setting name
	 * @param machine      the machine-wide configuration
	 * @param managed      the machine-wide managed configuration
	 * @param requirements the machine-wide requirements
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "unelevatedChildren")
	public void deniesNoReadsUnelevated(Map<String, String> selection, String machine, String managed,
		String requirements) throws IOException
	{
		// Requirement: R-I49
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-windows-sandbox-"));
			CodexSystemFolder system = machineLayers(root, machine, managed, requirements);
			for (Platform platform : List.of(Platform.WINDOWS_X64, Platform.WINDOWS_ARM64))
			{
				boolean deniesReads = CodexSessionSandbox.deniesReads(platform, selection, system);

				requireThat(deniesReads, "deniesReads").withContext(platform, "platform").isFalse();
			}
		}
	}

	/**
	 * Returns the configurations under which Codex runs a child in another implementation than {@code unelevated},
	 * none, or one that CAT cannot tell, in the order that {@link #unelevatedChildren()} gives them.
	 *
	 * @return each configuration
	 */
	@DataProvider
	public Object[][] deniedChildren()
	{
		String elevated = "[windows]\nsandbox = \"elevated\"\n";
		String unelevated = "[windows]\nsandbox = \"unelevated\"\n";
		return new Object[][]
			{
				{Map.of("windows.sandbox", "elevated"), "", "", ""},
				{Map.of("windows.sandbox", "mxc"), "", "", ""},
				{Map.of("windows.sandbox", "elevated"), unelevated, "", ""},
				// No layer selects an implementation
				{Map.of(), "", "", ""},
				// The managed configuration or the requirements select another implementation
				{Map.of("windows.sandbox", "unelevated"), "", elevated, ""},
				{Map.of("windows.sandbox", "unelevated"), "", "", "[windows]\nrequire_mxc = true\n"},
				// CAT cannot tell which implementation Codex chooses
				{Map.of("windows.sandbox", "unelevated", "features.prefer_mxc", "true"), "", "", ""},
				{Map.of("windows.sandbox", "unelevated", "windows.allow_mxc", "false"), "", "", ""},
				{Map.of("windows.sandbox", "unelevated"), "", "",
					"[windows]\nallowed_sandbox_implementations = [\"unelevated\"]\n"},
				{Map.of("windows.sandbox", "unelevated"), "[windows\n", "", ""}
			};
	}

	/**
	 * Verifies that a child that Codex runs in another Windows implementation than {@code unelevated}, or in one
	 * that CAT cannot tell, keeps its denied reads.
	 *
	 * @param selection    the parent's Windows selection, by dotted setting name
	 * @param machine      the machine-wide configuration
	 * @param managed      the machine-wide managed configuration
	 * @param requirements the machine-wide requirements
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "deniedChildren")
	public void keepsDeniedReadsOtherwise(Map<String, String> selection, String machine, String managed,
		String requirements) throws IOException
	{
		// Requirement: R-I49
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-windows-sandbox-"));
			CodexSystemFolder system = machineLayers(root, machine, managed, requirements);

			boolean deniesReads = CodexSessionSandbox.deniesReads(Platform.WINDOWS_X64, selection, system);

			requireThat(deniesReads, "deniesReads").isTrue();
		}
	}

	/**
	 * Verifies that a child off native Windows keeps its denied reads whatever its Windows selection names.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void keepsDeniedReadsOffWindows() throws IOException
	{
		// Requirement: R-I49
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-windows-sandbox-"));
			CodexSystemFolder system = machineLayers(root, "[windows]\nsandbox = \"unelevated\"\n", "", "");
			for (Platform platform : List.of(Platform.LINUX_X64, Platform.LINUX_ARM64, Platform.MACOS_X64,
				Platform.MACOS_ARM64))
			{
				boolean deniesReads = CodexSessionSandbox.deniesReads(platform, Map.of("windows.sandbox", "unelevated"),
					system);

				requireThat(deniesReads, "deniesReads").withContext(platform, "platform").isTrue();
			}
		}
	}

	/**
	 * Writes the machine-wide files below a folder, leaving out each file whose text is empty.
	 *
	 * @param root         the folder
	 * @param machine      the machine-wide configuration, written to {@code system/config.toml}
	 * @param managed      the machine-wide managed configuration, written to {@code system/managed_config.toml}
	 * @param requirements the machine-wide requirements, written to {@code system/requirements.toml}
	 * @return the machine-wide folder
	 * @throws IOException if a file cannot be written
	 */
	private static CodexSystemFolder machineLayers(Path root, String machine, String managed, String requirements)
		throws IOException
	{
		Path system = Files.createDirectories(root.resolve("system"));
		if (!machine.isEmpty())
			Files.writeString(system.resolve("config.toml"), machine);
		if (!managed.isEmpty())
			Files.writeString(system.resolve("managed_config.toml"), managed);
		if (!requirements.isEmpty())
			Files.writeString(system.resolve("requirements.toml"), requirements);
		return CodexSystemFolder.at(system);
	}

	/**
	 * Writes a session's Codex configuration and the machine-wide files below a folder, leaving out each file whose
	 * text is empty.
	 *
	 * @param root         the folder
	 * @param session      the configuration that the session takes its implementation from
	 * @param machine      the machine-wide configuration, written to {@code system/config.toml}
	 * @param requirements the machine-wide requirements, written to {@code system/requirements.toml}
	 * @return the session's configuration, which may not exist
	 * @throws IOException if a file cannot be written
	 */
	private static Path layers(Path root, String session, String machine, String requirements) throws IOException
	{
		Path system = Files.createDirectories(root.resolve("system"));
		Path configuration = root.resolve("config.toml");
		if (!session.isEmpty())
			Files.writeString(configuration, session);
		if (!machine.isEmpty())
			Files.writeString(system.resolve("config.toml"), machine);
		if (!requirements.isEmpty())
			Files.writeString(system.resolve("requirements.toml"), requirements);
		return configuration;
	}
}
