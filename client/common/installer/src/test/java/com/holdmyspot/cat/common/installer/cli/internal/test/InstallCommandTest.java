package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.trusted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SandboxWrites;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.WritablePathEntries;
import com.holdmyspot.cat.common.WriteGrant;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.cli.internal.Install;
import com.holdmyspot.cat.common.installer.engine.test.RegisteredProvider;
import com.holdmyspot.cat.common.installer.engine.test.RecordingMigrations;
import com.holdmyspot.cat.common.installer.engine.test.RecordingIntegration;
import com.holdmyspot.cat.common.installer.engine.test.Releases;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.release.EntryCommand;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
			requireThat(fixture.integration.registered(), "registered").
				isEqualTo(List.of(PluginVersion.parse(PluginArchive.VERSION)));
			Path policyFile = fixture.home.resolve("plugins/data/cat-cat/policy.json");
			TrustPolicy policy = TrustPolicy.read(policyFile);
			requireThat(policy.harnesses(), "harnesses").isEqualTo(Map.of("harness-a", fixture.home));
			requireThat(policy.command("harness-a").orElseThrow(), "command").isEqualTo(fixture.command);
			requireThat(outcome.policy(), "outcomePolicy").isEqualTo(policy);
			// The fixture plugin does not describe how to train its startup cache
			requireThat(outcome.startupCache(), "startupCache").isFalse();
			requireThat(fixture.migrations.runs(), "migrations").isEmpty();
			// The archive's release registers no migration, so no migration has shaped the new data
			requireThat(Files.exists(fixture.provider.migrationVersionFile(fixture.home), NOFOLLOW_LINKS),
				"dataVersion").isFalse();
		}
	}

	/**
	 * A first install without earlier data records the newest migration that the archive's release registers, which
	 * the new data already has, and runs no script.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void newDataRecordsNewestMigration() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-new-data-")));
			fixture.register("1.1\tsplit.sh\n");

			fixture.install();

			requireThat(fixture.migrations.runs(), "migrations").isEmpty();
			requireThat(Files.readString(fixture.provider.migrationVersionFile(fixture.home)), "dataVersion").
				isEqualTo("1.1\n");
		}
	}

	/**
	 * A first install refuses, changing no file, an archive whose release is older than the data version that kept
	 * data records, because a migration of a newer release has shaped that data; the refusal names both versions and
	 * install with a release at least as new as the data.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the fixture's versions are invalid
	 */
	@Test
	public void refusesOlderReleaseThanKeptData() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-newer-data-")));
			fixture.register("1.1\tsplit.sh\n");
			Path dataVersion = fixture.provider.migrationVersionFile(fixture.home);
			Files.createDirectories(dataVersion.getParent());
			Files.writeString(dataVersion, "1.3\n");
			Map<String, String> before = Releases.snapshot(fixture.root);

			try
			{
				fixture.install();
				throw new AssertionError("Install accepted an older release than the kept data");
			}
			catch (TrustException e)
			{
				requireThat(e.getMessage(), "message").contains("1.3").contains("1.2").contains("install");
			}
			requireThat(Releases.snapshot(fixture.root), "after").isEqualTo(before);
			requireThat(Files.exists(fixture.home.resolve("plugins/cat-marketplace"), NOFOLLOW_LINKS),
				"marketplace").isFalse();
			requireThat(fixture.migrations.runs(), "migrations").isEmpty();
			requireThat(fixture.integration.calls(), "calls").doesNotContain("register");
		}
	}

	/**
	 * Kept data that a newer release left, which registered no migration after the archive's release, records a data
	 * version no newer than the archive's release, so a first install installs and, running no migration, leaves the
	 * recorded data version unchanged.
	 * <p>
	 * The time goes to generating the release's signing key pair, writing a signed archive to disk, and installing
	 * its files into real directories.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void installsOverDataWithoutLaterMigration() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-kept-data-")));
			fixture.register("1.1\tsplit.sh\n");
			Path dataVersion = fixture.provider.migrationVersionFile(fixture.home);
			Files.createDirectories(dataVersion.getParent());
			Files.writeString(dataVersion, "1.1\n");

			fixture.install();

			requireThat(fixture.integration.registered(), "registered").
				isEqualTo(List.of(PluginVersion.parse(PluginArchive.VERSION)));
			requireThat(fixture.migrations.runs(), "migrations").isEmpty();
			requireThat(Files.readString(dataVersion), "dataVersion").isEqualTo("1.1\n");
		}
	}

	/**
	 * Data that an uninstall kept records its data version, so install runs, from the version folder, the migrations
	 * that the archive's release registers after that version, recording each migration's target version.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void migratesKeptData() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-migration-")));
			Path scripts = fixture.archive.resolve("install/migrations");
			Files.writeString(scripts.resolve("registry.tsv"), "1.1\tsplit.sh\n1.2\tmerge.sh\n");
			for (String script : List.of("split.sh", "split.ps1", "merge.sh", "merge.ps1"))
				Files.writeString(scripts.resolve(script), "exit 0\n");
			Path dataVersion = fixture.provider.migrationVersionFile(fixture.home);
			Files.createDirectories(dataVersion.getParent());
			Files.writeString(dataVersion, "1.1\n");

			fixture.install();

			// Windows runs each registered migration's PowerShell script
			String merge = "merge.sh";
			if (Platform.current().isWindows())
				merge = "merge.ps1";
			requireThat(fixture.migrations.runs().stream().map(RecordingMigrations.Run::script).toList(), "scripts").
				isEqualTo(List.of(fixture.versionFolder().resolve("install/migrations").resolve(merge)));
			requireThat(Files.readString(dataVersion), "dataVersion").isEqualTo("1.2\n");
		}
	}

	/**
	 * Verifies installation dispatches the selected version folder to the provider for companion installation.
	 * <p>
	 * The time goes to generating the release's signing key pair, writing a signed archive to disk, and installing
	 * its files into real directories.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void installsCompanionsFromVersionFolder() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-companions-")));

			fixture.install();

			requireThat(fixture.provider.companions(), "companions").isEqualTo(List.of(fixture.versionFolder()));
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
			fixture.integration.maintain(Map.of("settings.json", List.of("entry")));

			Install.Outcome outcome = fixture.install();

			// Windows runs the launcher's PowerShell script
			Path installer = fixture.versionFolder().resolve("bin/installer");
			if (Platform.current().isWindows())
				installer = fixture.versionFolder().resolve("bin/installer.ps1");
			requireThat(outcome.installer(), "installer").isEqualTo(installer);
			requireThat(fixture.integration.callsWithSubjects(), "calls").contains("installPermissions " + installer);
			requireThat(outcome.entries().recorded(), "permissions").isEqualTo(Map.of("settings.json", List.of("entry")));
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
			requireThat(outcome.entries().recorded(), "permissions").
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
	 * @return each condition's name, the type of the refusal and a fragment of its message
	 */
	@DataProvider
	public static Object[][] refusals()
	{
		return new Object[][]
		{
			{"configuration", IOException.class, "refused validateConfiguration"},
			{"discovery", TrustException.class, "was not found on PATH"},
			{"identity", IOException.class, "is not the harness-a program"},
			{"otherHarness", TrustException.class, "records the harness harness-b"},
			{"otherPlugin", TrustException.class, "holds no harness-a plugin"},
			{"version", TrustException.class, "does not name its plugin version"},
			{"relativeHome", TrustException.class, "must be an existing absolute directory"},
			{"missingHome", TrustException.class, "must be an existing absolute directory"}
		};
	}

	/**
	 * Install refuses, writing no marketplace folder and asking the harness for nothing, when the harness configuration
	 * is invalid, the harness program is not found or does not identify itself as the harness's program, the existing
	 * policy names another harness, the archive holds another harness's plugin or no plugin version, or the harness
	 * home is relative or missing.
	 *
	 * @param condition the refused condition
	 * @param refusal   the type of the exception that refuses it
	 * @param reason    a fragment of the refusal's message that names the condition
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the fixture's policies are invalid
	 */
	@Test(dataProvider = "refusals")
	public void refusesWithoutChange(String condition, Class<? extends Exception> refusal, String reason)
		throws IOException, TrustException
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
				requireThat(e.getClass().getName(), "refusal").isEqualTo(refusal.getName());
				requireThat(e.getMessage(), "message").contains(reason);
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
	 * A first install that the user runs from their home folder finds the harness program in a folder below that home,
	 * such as {@code ~/.local/bin}, where Claude Code's native installer puts it, and records it.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void firstInstallFindsProgramBelowUserHome() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-user-bin-")));
			Path program = RecordingIntegration.program(fixture.user.resolve(".local/bin"));
			fixture.environment.put("PATH", program.getParent().toString());

			fixture.install();

			requireThat(TrustPolicy.read(fixture.policyFile()).command("harness-a").orElseThrow().command(), "command").
				isEqualTo(List.of(program.toString()));
		}
	}

	/**
	 * Once install has recorded the harness program, a later install runs that program with its recorded environment
	 * and keeps it recorded, even when the search path now finds another program of the same name first.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void laterInstallReusesRecordedProgram() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-reuse-")));
			fixture.recordProgram();
			Path other = RecordingIntegration.program(fixture.root.resolve("other-tools")).getParent();
			fixture.environment.put("PATH", other + File.pathSeparator + fixture.environment.get("PATH"));

			fixture.install();

			requireThat(TrustPolicy.read(fixture.policyFile()).command("harness-a").orElseThrow(), "recorded").
				isEqualTo(fixture.command);
			requireThat(Set.copyOf(fixture.started), "started").isEqualTo(Set.of(fixture.command));
		}
	}

	/**
	 * A later install refuses, before anything changes and without taking another program from the search path, when
	 * the harness program that install recorded exists but no longer identifies itself as the harness's program; the
	 * refusal tells the user how to start over: delete the policy and run the CAT entry command.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the fixture's policy is invalid
	 */
	@Test
	public void laterInstallRefusesFailingProgram() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-identity-")));
			fixture.recordProgram();
			Path other = RecordingIntegration.program(fixture.root.resolve("other-tools")).getParent();
			fixture.environment.put("PATH", other + File.pathSeparator + fixture.environment.get("PATH"));
			fixture.integration.fail("requireIdentity");
			String before = Files.readString(fixture.policyFile());
			try
			{
				fixture.install();
				throw new AssertionError("Install accepted a recorded program that fails its identity check");
			}
			catch (TrustException | IOException e)
			{
				requireThat(e, "refusal").isInstanceOf(TrustException.class);
				requireThat(e.getMessage(), "message").contains(fixture.policyFile().toString()).
					contains(EntryCommands.forHarness("harness-a"));
			}
			requireThat(Files.exists(fixture.home.resolve("plugins/cat-marketplace"), NOFOLLOW_LINKS),
				"marketplace").isFalse();
			requireThat(fixture.integration.calls(), "calls").doesNotContain("register");
			requireThat(Files.readString(fixture.policyFile()), "policy").isEqualTo(before);
		}
	}

	/**
	 * Returns the ways in which the harness program that install recorded can be gone.
	 *
	 * @return each condition's name
	 */
	@DataProvider
	public static Object[][] goneRecordedPrograms()
	{
		if (Platform.current().isWindows())
			return new Object[][]{{"missing"}};
		return new Object[][]{{"missing"}, {"not executable"}};
	}

	/**
	 * A later install whose recorded harness program no longer exists, or is no longer executable, runs the first
	 * program of the harness on the installer's search path instead, as a first install finds it, and records that
	 * program in the policy; the install's message names the program that it replaced and the one that replaced it.
	 *
	 * @param condition the way in which the recorded program is gone
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test(dataProvider = "goneRecordedPrograms")
	public void laterInstallReplacesProgramThatIsGone(String condition) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-replace-")));
			fixture.recordProgram();
			Path replacement = fixture.replacementProgram();
			fixture.goneProgram(condition);

			Install.Outcome outcome = fixture.install();

			requireThat(TrustPolicy.read(fixture.policyFile()).command("harness-a").orElseThrow().command(), "recorded").
				isEqualTo(List.of(replacement.toString()));
			requireThat(outcome.policy().command("harness-a").orElseThrow().command(), "outcome").
				isEqualTo(List.of(replacement.toString()));
			requireThat(fixture.started.stream().map(HarnessCommand::command).distinct().toList(), "started").
				isEqualTo(List.of(List.of(replacement.toString())));
			requireThat(String.join(" ", outcome.entries().notices()), "notices").
				contains(fixture.command.command().getFirst()).contains(replacement.toString());
			requireThat(fixture.integration.calls(), "calls").contains("register");
		}
	}

	/**
	 * A replacement program that lies in a folder that a harness sandbox lets its commands write, directly or through
	 * a folder above it, comes with a warning that names the folder and the setting that grants the write.
	 *
	 * @param grant whether the grant names the replacement's folder or a folder above it
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test(dataProvider = "writableGrants")
	public void replacementFromWritableFolderWarns(String grant) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-replace-warns-")));
			fixture.recordProgram();
			Path replacement = fixture.replacementProgram();
			fixture.goneProgram("missing");
			Path granted = replacement.getParent();
			if (grant.equals("above"))
				granted = fixture.root;
			String setting = "sandbox.filesystem.allowWrite in " + fixture.user.resolve("settings.json");
			fixture.integration.allowingWrites(new SandboxWrites(List.of(new WriteGrant(granted, setting)),
				List.of()));

			Install.Outcome outcome = fixture.install();

			requireThat(String.join(" ", outcome.entries().notices()), "notices").
				contains(replacement.getParent().toString()).contains(setting);
		}
	}

	/**
	 * Returns how a grant can cover the folder of a replacement program.
	 *
	 * @return the grant's position relative to the folder
	 */
	@DataProvider
	public static Object[][] writableGrants()
	{
		return new Object[][]{{"folder"}, {"above"}};
	}

	/**
	 * A replacement program in a folder that no sandbox grant covers, or whose grant a denial covers, comes with no
	 * warning.
	 *
	 * @param reason the reason that the folder is not writable
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test(dataProvider = "unwritableFolders")
	public void replacementFromOtherFolderDoesNotWarn(String reason) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-replace-quiet-")));
			fixture.recordProgram();
			Path replacement = fixture.replacementProgram();
			fixture.goneProgram("missing");
			String setting = "sandbox.filesystem.allowWrite in " + fixture.user.resolve("settings.json");
			Path granted = Files.createDirectories(fixture.root.resolve("writable"));
			List<Path> denials = List.of();
			if (reason.equals("denied"))
			{
				granted = fixture.root;
				denials = List.of(replacement.getParent());
			}
			fixture.integration.allowingWrites(new SandboxWrites(List.of(new WriteGrant(granted, setting)),
				denials));

			Install.Outcome outcome = fixture.install();

			requireThat(String.join(" ", outcome.entries().notices()), "notices").contains(replacement.toString()).
				doesNotContain(setting);
		}
	}

	/**
	 * Returns the reasons that a folder is not writable by a sandbox.
	 *
	 * @return each reason's name
	 */
	@DataProvider
	public static Object[][] unwritableFolders()
	{
		return new Object[][]{{"ungranted"}, {"denied"}};
	}

	/**
	 * The sandbox of another harness whose home the installer finds also gives the warning: a command of that harness
	 * can write the replacement program's folder.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void replacementWarnsOfOtherHarnessSandbox() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-replace-other-")));
			fixture.recordProgram();
			Path replacement = fixture.replacementProgram();
			fixture.goneProgram("missing");
			String setting = "writable_roots in " + fixture.user.resolve("config.toml");
			Files.createDirectories(fixture.user.resolve("harness-b-home"));
			fixture.others.add(new RecordingIntegration("harness-b").allowingWrites(new SandboxWrites(
				List.of(new WriteGrant(replacement.getParent(), setting)), List.of())));

			Install.Outcome outcome = fixture.install();

			requireThat(String.join(" ", outcome.entries().notices()), "notices").contains(setting);
		}
	}

	/**
	 * A warning for a replacement from a folder that a sandbox lets its commands write states which commands the check
	 * covers, so that the user does not take it for a check of commands that run outside the sandbox.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void replacementWarningStatesItsCoverage() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-replace-coverage-")));
			fixture.recordProgram();
			Path replacement = fixture.replacementProgram();
			fixture.goneProgram("missing");
			fixture.integration.allowingWrites(new SandboxWrites(List.of(new WriteGrant(replacement.getParent(),
				"sandbox.filesystem.allowWrite")), List.of()));

			Install.Outcome outcome = fixture.install();

			requireThat(String.join(" ", outcome.entries().notices()), "notices").contains(WritablePathEntries.COVERAGE);
		}
	}

	/**
	 * A grant that CAT cannot be sure of is reported as one that may be writable, and what CAT could not read is
	 * listed, so that the user does not take a missing warning for a folder that is safe.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void replacementWarnsOfPossibleGrants() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-replace-possible-")));
			fixture.recordProgram();
			Path replacement = fixture.replacementProgram();
			fixture.goneProgram("missing");
			String setting = "sandbox.filesystem.allowWrite in " + fixture.user.resolve("settings.json");
			fixture.integration.allowingWrites(new SandboxWrites(List.of(new WriteGrant(replacement.getParent(),
				setting, true)), List.of(), List.of("the settings file managed-settings.json cannot be parsed")));

			Install.Outcome outcome = fixture.install();

			String notices = String.join(" ", outcome.entries().notices());
			requireThat(notices, "notices").contains(replacement.getParent().toString()).contains("may be writable").
				contains(setting).contains("managed-settings.json cannot be parsed");
		}
	}

	/**
	 * A harness that reports no sandbox grant, which is what it does when it has no sandbox in effect, adds no
	 * sandbox warning to the message, and neither does a coverage statement stand alone.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void replacementDoesNotWarnWithoutSandbox() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-replace-nosandbox-")));
			fixture.recordProgram();
			Path replacement = fixture.replacementProgram();
			fixture.goneProgram("missing");
			fixture.integration.allowingWrites(new SandboxWrites(List.of(), List.of()));

			Install.Outcome outcome = fixture.install();

			String notices = String.join(" ", outcome.entries().notices());
			requireThat(notices, "notices").contains(replacement.toString()).doesNotContain("writable").
				doesNotContain(WritablePathEntries.COVERAGE);
		}
	}

	/**
	 * A harness with no sandbox in effect that still cannot read one of its settings tells the user what it could not
	 * read and names no folder as writable, so that the user does not take the missing warning for a checked folder.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void replacementReportsUnreadableSettings() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-replace-unreadable-")));
			fixture.recordProgram();
			fixture.replacementProgram();
			fixture.goneProgram("missing");
			fixture.integration.allowingWrites(new SandboxWrites(List.of(), List.of(),
				List.of("the settings file settings.json cannot be parsed")));

			Install.Outcome outcome = fixture.install();

			String notices = String.join(" ", outcome.entries().notices());
			requireThat(notices, "notices").contains("could not read everything").
				contains("settings.json cannot be parsed").contains(WritablePathEntries.COVERAGE).
				doesNotContain("Warning:");
		}
	}

	/**
	 * The installer asks each harness for the sandbox writes of the project that holds the folder that the user ran it
	 * from.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void replacementReadsWorkingDirProject() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-replace-project-")));
			fixture.recordProgram();
			fixture.replacementProgram();
			fixture.goneProgram("missing");
			Files.createDirectories(fixture.user.resolve("harness-b-home"));
			RecordingIntegration other = new RecordingIntegration("harness-b");
			fixture.others.add(other);

			fixture.install();

			requireThat(fixture.integration.askedWorkingDirectories(), "own").isEqualTo(List.of(fixture.workingDirectory));
			requireThat(other.askedWorkingDirectories(), "other").isEqualTo(List.of(fixture.workingDirectory));
		}
	}

	/**
	 * A first install, which records no program yet, takes the program from the search path and gives no warning about
	 * writable folders, even when a sandbox grant covers the program's folder: the folders of the user's search path
	 * are the user's choice.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void firstInstallGivesNoWritableFolderWarning() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-first-quiet-")));
			String setting = "sandbox.filesystem.allowWrite in " + fixture.user.resolve("settings.json");
			fixture.integration.allowingWrites(new SandboxWrites(List.of(new WriteGrant(fixture.root, setting)),
				List.of()));

			Install.Outcome outcome = fixture.install();

			requireThat(String.join(" ", outcome.entries().notices()), "notices").doesNotContain(setting);
		}
	}

	/**
	 * The install's message carries the harness's recommendation on its sandbox for the install's platform, and the
	 * install continues.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if install refuses
	 */
	@Test
	public void messageCarriesSandboxRecommendation() throws IOException, TrustException
	{
		// Requirement: R-I47
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(testDirectory("install-recommendation-")));
			String recommendation = "Select the sandbox implementation with the fewest known weaknesses.";
			fixture.integration.recommendingSandbox(recommendation);

			Install.Outcome outcome = fixture.install();

			requireThat(String.join(" ", outcome.entries().notices()), "notices").contains(recommendation);
			requireThat(fixture.integration.recommendationPlatforms(), "platforms").
				isEqualTo(List.of(Platform.current()));
			requireThat(Files.isDirectory(fixture.versionFolder()), "installed").isTrue();
		}
	}

	/**
	 * Install without a harness home installs at the home that the user's environment selects.
	 * <p>
	 * The time goes to generating the release's signing key pair, writing a signed archive to disk, and installing
	 * its files into real directories.
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
			requireThat(outcome.entries().recorded(), "permissions").isEqualTo(Map.of("settings.json", List.of("entry")));
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
		private final Map<String, String> environment = new HashMap<>();
		private final RecordingIntegration integration = new RecordingIntegration();
		private final RegisteredProvider provider = new RegisteredProvider();
		private final RecordingMigrations migrations = new RecordingMigrations();
		private final List<HarnessIntegration> others = new ArrayList<>();
		private final HarnessCommand command;
		private final List<HarnessCommand> started = new ArrayList<>();
		private Path requestedHome;
		/**
		 * The folder that the fixture's installer runs from.
		 */
		private final Path workingDirectory;

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
			this.user = Files.createDirectories(root.resolve("user"));
			this.workingDirectory = Files.createDirectories(root.resolve("working"));
			environment.put("HOME", user.toString());
			environment.put(Platform.current().homeVariable(), user.toString());
			environment.put("XDG_DATA_HOME", root.resolve("xdg-data").toString());
			environment.put("XDG_CONFIG_HOME", root.resolve("xdg-config").toString());
			environment.put("LOCALAPPDATA", root.resolve("local-app-data").toString());
			Path program = RecordingIntegration.program(root.resolve("tools"));
			environment.put("PATH", program.getParent().toString());
			environment.putAll(RecordingIntegration.windowsVariables());
			Map<String, String> recorded = new HashMap<>(RecordingIntegration.recordedWindowsVariables());
			recorded.put("HARNESS_A_HOME", home.toString());
			recorded.put("PATH", program.getParent().toString());
			this.command = new HarnessCommand(List.of(program.toString()), recorded);
		}

		/**
		 * Returns the installation's policy.
		 *
		 * @return the policy file in the harness's plugin data folder
		 */
		private Path policyFile()
		{
			return home.resolve("plugins/data/cat-cat/policy.json");
		}

		/**
		 * Writes the policy of an earlier install, which recorded {@link #command} as the harness program.
		 *
		 * @throws IOException    if the policy cannot be written
		 * @throws TrustException if the policy is invalid
		 */
		private void recordProgram() throws IOException, TrustException
		{
			Files.createDirectories(policyFile().getParent());
			TrustPolicy.of(List.of(), URI.create("https://releases.example/cat"), ReleaseVersion.parse("1.0"), 1,
				Map.of("harness-a", home), Map.of("harness-a", command)).write(policyFile(), replacer());
		}

		/**
		 * Puts another harness program first on the installer's search path.
		 *
		 * @return the program
		 * @throws IOException if the program cannot be written
		 */
		private Path replacementProgram() throws IOException
		{
			Path program = RecordingIntegration.program(root.resolve("other-tools"));
			environment.put("PATH", program.getParent() + File.pathSeparator + environment.get("PATH"));
			return program;
		}

		/**
		 * Makes the recorded program unusable because it is gone.
		 *
		 * @param condition {@code missing} to delete it, or {@code not executable} to keep it without execute rights
		 * @throws IOException if the program cannot be changed
		 */
		private void goneProgram(String condition) throws IOException
		{
			Path program = Path.of(command.command().getFirst());
			switch (condition)
			{
				case "missing" -> Files.delete(program);
				case "not executable" -> Files.setPosixFilePermissions(program,
					PosixFilePermissions.fromString("rw-------"));
				default -> throw new AssertionError(condition);
			}
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
		 * Replaces the migration registry of the archive's release, giving each registered script an empty body.
		 *
		 * @param registry the registry's lines
		 * @throws IOException if the registry or a script cannot be written
		 */
		private void register(String registry) throws IOException
		{
			Path scripts = archive.resolve("install/migrations");
			Files.writeString(scripts.resolve("registry.tsv"), registry);
			for (String line : registry.split("\n"))
			{
				String script = line.substring(line.indexOf('\t') + 1);
				Files.writeString(scripts.resolve(script), "exit 0\n");
				Files.writeString(scripts.resolve(script.replace(".sh", ".ps1")), "exit 0\n");
			}
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
			Install install = new Install(archive, Platform.current(), replacer(),
				(recorded, recordedHome) ->
				{
					started.add(recorded);
					return RecordingIntegration.cli();
				}, () -> false, migrations, new EntryCommand(Platform.current(), environment, null),
				interval -> () ->
				{
				});
			return install.install("harness-a", requestedHome, environment, user, workingDirectory, provider,
				integration, others, _ -> false);
		}
	}
}
