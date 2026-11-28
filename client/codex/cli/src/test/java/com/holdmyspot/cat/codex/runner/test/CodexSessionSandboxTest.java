package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import com.holdmyspot.cat.codex.runner.CodexSessionSandbox;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
