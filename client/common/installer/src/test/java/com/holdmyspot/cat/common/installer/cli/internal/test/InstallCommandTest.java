package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.cli.internal.Install;
import com.holdmyspot.cat.common.installer.engine.test.LiveProvider;
import com.holdmyspot.cat.common.installer.engine.test.RecordingIntegration;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests a first install from an extracted plugin archive: the harness installs the plugin from CAT's marketplace into
 * its version folder, and the installer's policy, permission entries and sandbox entries follow.
 */
public final class InstallCommandTest
{
	/**
	 * Creates the test.
	 */
	public InstallCommandTest()
	{
	}

	/**
	 * Install writes the plugin into the harness's marketplace folder and asks the harness to install the archive's
	 * version, which places it in its version folder, bound to the Java runtime in that folder; the policy goes into
	 * the harness's plugin data folder.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void installsThroughHarness() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-harness-")));

			Install.Outcome outcome = fixture.install();

			Path marketplace = fixture.home.resolve("plugins/cat-marketplace/plugins/cat");
			requireThat(Files.readString(marketplace.resolve("bin/VERSION")), "marketplace").
				isEqualTo(PluginArchive.VERSION + "\n");
			Path versionFolder = fixture.versionFolder();
			requireThat(outcome.versionFolder(), "versionFolder").isEqualTo(versionFolder);
			requireThat(outcome.version().toString(), "version").isEqualTo(PluginArchive.VERSION);
			requireThat(Files.isRegularFile(versionFolder.resolve(".harness-a/plugin.json")), "installed").isTrue();
			requireThat(Files.readString(versionFolder.resolve("bin/runtime-binding")), "binding").
				isEqualTo(versionFolder.resolve("bin/runtime") + "\n");
			requireThat(fixture.integration.calls(), "calls").isEqualTo(List.of("requireIdentity",
				"validateConfiguration", "register", "installPermissions"));
			requireThat(fixture.integration.registered(), "registered").
				isEqualTo(List.of(PluginVersion.parse(PluginArchive.VERSION)));
			Path policyFile = fixture.home.resolve("plugins/data/cat-cat/policy.json");
			TrustPolicy policy = TrustPolicy.read(policyFile);
			requireThat(policy.harnesses(), "harnesses").isEqualTo(Map.of("harness-a", fixture.home));
			requireThat(policy.command("harness-a").orElseThrow(), "command").isEqualTo(fixture.command);
			requireThat(outcome.policy(), "outcomePolicy").isEqualTo(policy);
			// The fixture plugin does not describe how to train its startup cache
			requireThat(outcome.startupCache(), "startupCache").isFalse();
		}
	}

	/**
	 * The permission entries that install adds name the installer launcher in the version folder that the harness loads,
	 * and install records them in the plugin data folder.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void entriesNameVersionFolderInstaller() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-entries-")));
			fixture.integration.adding(Map.of("settings.json", List.of("entry")));

			Install.Outcome outcome = fixture.install();

			Path installer = fixture.versionFolder().resolve("bin/installer");
			requireThat(outcome.installer(), "installer").isEqualTo(installer);
			requireThat(fixture.integration.callsWithSubjects(), "calls").contains("installPermissions " + installer);
			requireThat(outcome.permissions(), "permissions").isEqualTo(Map.of("settings.json", List.of("entry")));
			requireThat(Files.readString(fixture.home.resolve("plugins/data/cat-cat/state/permissions/harness-a.json")),
				"record").contains("settings.json").contains("entry");
		}
	}

	/**
	 * Install adds the sandbox entries for each other harness's CAT installation, at the home that the user's
	 * environment selects, and for the capacity locks in the user's home, and records them with the permission
	 * entries.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void sandboxEntriesNameOtherHarness() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-sandbox-")));
			fixture.others.add(new RecordingIntegration("harness-b"));
			fixture.integration.sandboxing(Map.of("settings.json#sandbox", List.of("other")));

			Install.Outcome outcome = fixture.install();

			requireThat(fixture.integration.callsWithSubjects(), "calls").contains("installSandboxEntries " +
				fixture.user.resolve("harness-b-home") + " " + fixture.user.resolve(".cat/locks"));
			requireThat(outcome.permissions(), "permissions").
				isEqualTo(Map.of("settings.json#sandbox", List.of("other")));
		}
	}

	/**
	 * Install writes nothing in the XDG data and configuration folders, the local application data folder or the user's
	 * home; everything it writes lies in the harness home.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void writesOnlyInHarnessHome() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-only-home-")));
			fixture.others.add(new RecordingIntegration("harness-b"));

			fixture.install();

			for (String folder : List.of("xdg-data", "xdg-config", "local-app-data"))
				requireThat(Files.exists(fixture.root.resolve(folder), NOFOLLOW_LINKS), folder).isFalse();
			try (Stream<Path> entries = Files.list(fixture.user))
			{
				requireThat(entries.toList(), "userHome").isEmpty();
			}
		}
	}

	/**
	 * Returns install conditions that must be refused before anything changes.
	 *
	 * @return each condition's name
	 */
	@DataProvider
	public static Object[][] refusals()
	{
		return new Object[][]{{"configuration"}, {"discovery"}, {"identity"}, {"otherHarness"}, {"otherPlugin"},
			{"version"}, {"relativeHome"}, {"missingHome"}};
	}

	/**
	 * Install refuses, writing no marketplace folder and asking the harness for nothing, when the harness configuration
	 * is invalid, the harness program is not found or does not identify itself as the harness's program, the existing
	 * policy names another harness, the archive holds another harness's plugin or no plugin version, or the harness
	 * home is relative or missing.
	 *
	 * @param condition the refused condition
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the fixture's policies are invalid
	 */
	@Test(dataProvider = "refusals")
	public void refusesWithoutChange(String condition) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-refused-")));
			Path policyFile = fixture.home.resolve("plugins/data/cat-cat/policy.json");
			switch (condition)
			{
				case "configuration" -> fixture.integration.fail("validateConfiguration");
				case "discovery" -> Files.delete(Path.of(fixture.command.command().getFirst()));
				case "identity" -> fixture.integration.fail("requireIdentity");
				case "otherHarness" ->
				{
					Files.createDirectories(policyFile.getParent());
					TrustPolicy.of(List.of(), URI.create("https://releases.example/cat"), ReleaseVersion.parse("1.0"),
						1, Map.of("harness-b", fixture.home)).write(policyFile, replacer());
				}
				case "otherPlugin" -> Files.move(fixture.archive.resolve(".harness-a"),
					fixture.archive.resolve(".harness-b"));
				case "version" -> Files.writeString(fixture.archive.resolve("bin/VERSION"), "1.2\n");
				case "relativeHome" -> fixture.requestedHome = Path.of("harness-home");
				case "missingHome" -> fixture.requestedHome = fixture.root.resolve("absent");
				default -> throw new AssertionError(condition);
			}
			String before = "";
			if (Files.exists(policyFile))
				before = Files.readString(policyFile);
			try
			{
				fixture.install();
				throw new AssertionError("Install accepted " + condition);
			}
			catch (TrustException | IOException e)
			{
				requireThat(e.getMessage(), "message").isNotBlank();
			}
			requireThat(Files.exists(fixture.home.resolve("plugins/cat-marketplace"), NOFOLLOW_LINKS),
				"marketplace").isFalse();
			requireThat(fixture.integration.calls(), "calls").doesNotContain("register");
			if (before.isEmpty())
				requireThat(Files.exists(policyFile, NOFOLLOW_LINKS), "policy").isFalse();
			else
				requireThat(Files.readString(policyFile), "policy").isEqualTo(before);
		}
	}

	/**
	 * Install without a harness home installs at the home that the user's environment selects.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void installsAtEnvironmentHome() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-default-home-")));
			fixture.environment.put("HARNESS_A_HOME", fixture.home.toString());
			fixture.requestedHome = null;

			Install.Outcome outcome = fixture.install();

			requireThat(outcome.versionFolder(), "versionFolder").isEqualTo(fixture.versionFolder());
		}
	}

	/**
	 * Install removes the recorded entries that the integration no longer maintains and keeps the record of the
	 * entries that it still maintains.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void removesObsoletePermissions() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-obsolete-")));
			Path record = fixture.home.resolve("plugins/data/cat-cat/state/permissions/harness-a.json");
			Files.createDirectories(record.getParent());
			Files.writeString(record, "{\"settings.json\":[\"entry\",\"retired\"]}\n");
			fixture.integration.maintain(Map.of("settings.json", List.of("entry")));

			Install.Outcome outcome = fixture.install();

			requireThat(fixture.integration.removed(), "removed").
				isEqualTo(List.of(Map.of("settings.json", List.of("retired"))));
			requireThat(outcome.permissions(), "permissions").isEqualTo(Map.of("settings.json", List.of("entry")));
			requireThat(Files.readString(record), "record").contains("entry").doesNotContain("retired");
		}
	}

	/**
	 * Returns an atomic replacer for isolated temporary files, which never need a retry pause.
	 *
	 * @return the replacer
	 */
	private static FileReplacer replacer()
	{
		return new FileReplacer(Platform.current(), () ->
		{
		}, Clock.systemUTC());
	}

	/**
	 * An extracted plugin archive, the user's environment and one harness home.
	 */
	private static final class Fixture
	{
		private final Path root;
		private final Path archive;
		private final Path home;
		private final Path user;
		private final Path cwd;
		private final Map<String, String> environment = new HashMap<>();
		private final RecordingIntegration integration = new RecordingIntegration();
		private final List<HarnessIntegration> others = new ArrayList<>();
		private final HarnessCommand command;
		private Path requestedHome;

		/**
		 * Creates the archive and the user's directories.
		 *
		 * @param directory the test directory
		 * @throws IOException    if the fixture cannot be created
		 * @throws TrustException if the release's policy is invalid
		 */
		private Fixture(Path directory) throws IOException, TrustException
		{
			this.root = directory.toRealPath();
			this.archive = PluginArchive.create(root.resolve("download"), List.of(trusted("release-key",
				keyPair())));
			this.home = Files.createDirectories(root.resolve("harness-home"));
			this.requestedHome = home;
			this.cwd = Files.createDirectories(root.resolve("project"));
			this.user = Files.createDirectories(root.resolve("user"));
			environment.put("HOME", user.toString());
			environment.put("XDG_DATA_HOME", root.resolve("xdg-data").toString());
			environment.put("XDG_CONFIG_HOME", root.resolve("xdg-config").toString());
			environment.put("LOCALAPPDATA", root.resolve("local-app-data").toString());
			Path program = RecordingIntegration.program(root.resolve("tools"));
			environment.put("PATH", program.getParent().toString());
			this.command = new HarnessCommand(List.of(program.toString()), Map.of("HARNESS_A_HOME", home.toString(),
				"PATH", program.getParent().toString()));
		}

		/**
		 * Returns the version folder into which the harness installs the archive's plugin.
		 *
		 * @return the folder
		 */
		private Path versionFolder()
		{
			return home.resolve("plugins/cache/cat/cat").resolve(PluginArchive.VERSION);
		}

		/**
		 * Runs install from the archive.
		 *
		 * @return the outcome
		 * @throws IOException    if install fails
		 * @throws TrustException if install refuses
		 */
		private Install.Outcome install() throws IOException, TrustException
		{
			Install install = new Install(archive, Platform.LINUX_X64, replacer(),
				(recorded, recordedHome) -> RecordingIntegration.cli(), _ -> false);
			return install.install("harness-a", requestedHome, cwd, environment, user, new LiveProvider(),
				integration, others);
		}
	}
}
