package com.holdmyspot.cat.codex.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.installer.CodexWindowsSandbox;
import com.holdmyspot.cat.codex.installer.CodexWriteGrants.Launch;
import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Verifies that {@link CodexWindowsSandbox} recommends Codex's {@code mxc} Windows sandbox implementation on native
 * Windows whenever the implementation that Codex applies is another one, none, or one that CAT cannot tell, and
 * recommends nothing when it is {@code mxc} or off Windows.
 */
public final class CodexWindowsSandboxTest
{
	/**
	 * Creates the test.
	 */
	public CodexWindowsSandboxTest()
	{
	}

	/**
	 * Returns the configurations under which Codex applies an implementation other than {@code mxc}, none, or one that
	 * CAT cannot tell: the machine-wide configuration, the user's configuration, the profile file that the launch
	 * names, the launch's {@code -c} overrides and the machine-wide requirements, each empty if absent.
	 *
	 * @return each configuration
	 */
	@DataProvider
	public Object[][] recommending()
	{
		String elevated = "[windows]\nsandbox = \"elevated\"\n";
		String unelevated = "[windows]\nsandbox = \"unelevated\"\n";
		String mxc = "[windows]\nsandbox = \"mxc\"\n";
		return new Object[][]
			{
				{"", elevated, "", List.of(), ""},
				{"", unelevated, "", List.of(), ""},
				{elevated, "", "", List.of(), ""},
				// No value in any layer
				{"", "", "", List.of(), ""},
				// The user's configuration wins over the machine's
				{mxc, elevated, "", List.of(), ""},
				// The profile file wins over the user's configuration
				{"", mxc, unelevated, List.of(), ""},
				// An override wins over every file
				{"", mxc, "", List.of("windows.sandbox=\"elevated\""), ""},
				// CAT cannot tell how Codex combines these with the selection
				{"", mxc + "allow_mxc = true\n", "", List.of(), ""},
				{"", mxc + "[features]\nprefer_mxc = true\n", "", List.of(), ""},
				{"", elevated, "", List.of("features.prefer_mxc=false"), ""},
				{"", "", "", List.of("windows.allow_mxc=true"), ""},
				{"", mxc, "", List.of(), "[windows]\nallow_mxc = true\n"},
				{"", mxc, "", List.of(), "[windows]\nallowed_sandbox_implementations = [\"elevated\", \"unelevated\"]\n"},
				// A file that CAT cannot parse
				{"", "[windows\nsandbox = \"mxc\"\n", "", List.of(), ""}
			};
	}

	/**
	 * Verifies that the recommendation appears on both Windows architectures, and names the setting and the Windows
	 * builds that run {@code mxc}.
	 *
	 * @param machine      the machine-wide configuration
	 * @param user         the user's configuration
	 * @param profile      the profile file that the launch names, or empty if the launch names none
	 * @param overrides    the launch's {@code -c} overrides
	 * @param requirements the machine-wide requirements
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "recommending")
	public void recommendsMxc(String machine, String user, String profile, List<String> overrides,
		String requirements) throws IOException
	{
		// Requirement: R-I47
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup, machine, user, profile, requirements);
			for (Platform platform : List.of(Platform.WINDOWS_X64, Platform.WINDOWS_ARM64))
			{
				Optional<String> recommendation = fixture.recommendation(platform, Optional.of(fixture.launch(profile,
					overrides)));

				requireThat(recommendation.orElse(""), "recommendation").contains("[windows] sandbox = \"mxc\"").
					contains("26100.9278").contains("26200.9278").contains("Windows Server");
			}
		}
	}

	/**
	 * Verifies that the recommendation appears when CAT cannot read the session's command line, which may hold a
	 * selection that CAT does not see.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void recommendsMxcForUnknownLaunch() throws IOException
	{
		// Requirement: R-I47
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup, "", "[windows]\nsandbox = \"mxc\"\n", "", "");

			Optional<String> recommendation = fixture.recommendation(Platform.WINDOWS_X64, Optional.empty());

			requireThat(recommendation.orElse(""), "recommendation").contains("[windows] sandbox = \"mxc\"");
		}
	}

	/**
	 * Returns the configurations under which Codex applies {@code mxc}, in the order that {@link #recommending()} gives
	 * them.
	 *
	 * @return each configuration
	 */
	@DataProvider
	public Object[][] applyingMxc()
	{
		String elevated = "[windows]\nsandbox = \"elevated\"\n";
		String mxc = "[windows]\nsandbox = \"mxc\"\n";
		return new Object[][]
			{
				{"", mxc, "", List.of(), ""},
				{mxc, "", "", List.of(), ""},
				{"", elevated, mxc, List.of(), ""},
				{"", elevated, "", List.of("windows.sandbox=\"mxc\""), ""},
				// The requirements make Codex run mxc whatever the layers select
				{"", elevated, "", List.of(), "[windows]\nrequire_mxc = true\n"},
				{"", "", "", List.of(), "[windows]\nrequire_mxc = true\n"}
			};
	}

	/**
	 * Verifies that no recommendation appears when Codex applies {@code mxc}.
	 *
	 * @param machine      the machine-wide configuration
	 * @param user         the user's configuration
	 * @param profile      the profile file that the launch names, or empty if the launch names none
	 * @param overrides    the launch's {@code -c} overrides
	 * @param requirements the machine-wide requirements
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "applyingMxc")
	public void recommendsNothingUnderMxc(String machine, String user, String profile, List<String> overrides,
		String requirements) throws IOException
	{
		// Requirement: R-I47
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup, machine, user, profile, requirements);

			Optional<String> recommendation = fixture.recommendation(Platform.WINDOWS_X64,
				Optional.of(fixture.launch(profile, overrides)));

			requireThat(recommendation, "recommendation").isEmpty();
		}
	}

	/**
	 * Verifies that a session that ignores the user's configuration takes its implementation from the other layers.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void skipsUserConfigurationLaunchIgnores() throws IOException
	{
		// Requirement: R-I47
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup, "[windows]\nsandbox = \"mxc\"\n",
				"[windows]\nsandbox = \"elevated\"\n", "", "");
			Launch ignoring = new Launch(List.of(), Optional.empty(), Optional.empty(), false, List.of(), true);

			Optional<String> recommendation = fixture.recommendation(Platform.WINDOWS_X64, Optional.of(ignoring));

			requireThat(recommendation, "recommendation").isEmpty();
		}
	}

	/**
	 * Verifies that no recommendation appears off Windows, where Codex has no {@code mxc}, whatever the configuration
	 * selects.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void recommendsNothingOffWindows() throws IOException
	{
		// Requirement: R-I47
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup, "", "[windows]\nsandbox = \"elevated\"\n", "", "");
			for (Platform platform : List.of(Platform.LINUX_X64, Platform.LINUX_ARM64, Platform.MACOS_X64,
				Platform.MACOS_ARM64))
			{
				Optional<String> recommendation = fixture.recommendation(platform,
					Optional.of(fixture.launch("", List.of())));

				requireThat(recommendation, "recommendation").isEmpty();
			}
		}
	}

	/**
	 * The machine-wide folder, Codex's home and their files.
	 */
	private static final class Fixture
	{
		private final Path system;
		private final Path codexHome;

		/**
		 * Creates the fixture's folders and writes each file that is not empty.
		 *
		 * @param cleanup      deletes the folders when the test ends
		 * @param machine      the machine-wide configuration
		 * @param user         the user's configuration
		 * @param profile      the profile file {@code work.config.toml}
		 * @param requirements the machine-wide requirements
		 * @throws IOException if a file cannot be written
		 */
		private Fixture(TemporaryDirectories cleanup, String machine, String user, String profile,
			String requirements) throws IOException
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-windows-sandbox-"));
			this.system = Files.createDirectories(root.resolve("system"));
			this.codexHome = Files.createDirectories(root.resolve("home/.codex"));
			write(system.resolve("config.toml"), machine);
			write(codexHome.resolve("config.toml"), user);
			write(codexHome.resolve("work.config.toml"), profile);
			write(system.resolve("requirements.toml"), requirements);
		}

		/**
		 * Writes a file unless its content is empty.
		 *
		 * @param file    the file
		 * @param content the content
		 * @throws IOException if the file cannot be written
		 */
		private static void write(Path file, String content) throws IOException
		{
			if (!content.isEmpty())
				Files.writeString(file, content);
		}

		/**
		 * Returns a launch with the given options.
		 *
		 * @param profile   empty for no profile, otherwise the launch names the profile {@code work}
		 * @param overrides the {@code -c} overrides
		 * @return the launch
		 */
		private Launch launch(String profile, List<String> overrides)
		{
			Optional<String> named = Optional.empty();
			if (!profile.isEmpty())
				named = Optional.of("work");
			return new Launch(overrides, named, Optional.empty(), false, List.of(), false);
		}

		/**
		 * Returns the recommendation for this fixture's files.
		 *
		 * @param platform the platform
		 * @param launch   the session's command line, or empty if it is not known
		 * @return the recommendation
		 */
		private Optional<String> recommendation(Platform platform, Optional<Launch> launch)
		{
			return CodexWindowsSandbox.recommendation(platform, CodexSystemFolder.at(system), codexHome, launch);
		}
	}
}
