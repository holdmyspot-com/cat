package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests the PowerShell scripts that install and remove CAT's Codex plugin on Windows. Each test runs a script the way
 * that the installation instructions do, as a script block in Windows PowerShell, with a {@code codex.cmd} that records
 * each call instead of Codex. A test that downloads a release replaces the cmdlets that download files with functions
 * that copy them from a folder.
 */
public final class CodexWindowsInstallerTest
{
	/**
	 * The folder that holds the Codex installation scripts, found from this module's {@code target/test-classes}
	 * folder.
	 */
	private static final Path INSTALL = installFolder();
	/**
	 * The name of the Windows release archive of the Codex plugin, without its extension.
	 */
	private static final String RELEASE_NAME = "cat-codex-v1.0-windows-x64";

	/**
	 * Returns the folder that holds the Codex installation scripts.
	 *
	 * @return the folder
	 */
	private static Path installFolder()
	{
		try
		{
			Path classes = Path.of(CodexWindowsInstallerTest.class.getProtectionDomain().getCodeSource().getLocation().
				toURI());
			// target/test-classes is two levels below the module, which is two levels below the client directory
			return classes.resolve("../../../../codex/lifecycle/src/main/resources/install").normalize();
		}
		catch (URISyntaxException e)
		{
			throw new AssertionError(e);
		}
	}

	/** Creates a test instance. */
	public CodexWindowsInstallerTest()
	{
	}

	/**
	 * Verifies that installing an extracted artifact copies it into CAT's marketplace, installs its custom agents,
	 * records the data version, and asks Codex to replace the plugin.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void installsExtractedArtifact() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path artifact = fixture.artifact();

			Result result = fixture.run(install("-Source " + literal(artifact)));

			requireThat(result.status(), "status").withContext(result.output(), "output").isEqualTo(0);
			Path marketplace = fixture.codexHome.resolve("plugins").resolve("cat-marketplace");
			requireThat(Files.readString(marketplace.resolve(".agents/plugins/marketplace.json")), "marketplace").
				isEqualTo(Files.readString(artifact.resolve("install/codex-marketplace.json")));
			requireThat(Files.isRegularFile(marketplace.resolve("plugins/cat/.codex-plugin/plugin.json")),
				"plugin.json").isTrue();
			requireThat(Files.readString(fixture.codexHome.resolve("agents/cat-reviewer.toml")), "agent").
				isEqualTo(Files.readString(artifact.resolve("agents/codex/reviewer.toml")));
			Path data = fixture.codexHome.resolve("plugins/data/cat-cat");
			requireThat(Files.readString(data.resolve("codex-agent-files")).strip(), "agents").
				isEqualTo("cat-reviewer.toml");
			requireThat(Files.readString(data.resolve("migration-version")).strip(), "version").isEqualTo("1.0");
			requireThat(fixture.commands(), "commands").isEqualTo(List.of(
				"codex plugin remove cat@cat",
				"codex plugin marketplace remove cat",
				"codex plugin marketplace add \"" + marketplace + "\"",
				"codex plugin add cat@cat",
				"plugin-info"));
		}
	}

	/**
	 * Verifies that installing the release tag downloads the archive for Windows, verifies its checksum, and installs
	 * the plugin that it holds.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void installsVerifiedRelease() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.release(fixture.artifact(), false);

			Result result = fixture.run(fixture.fakeDownloads() + install("-Source v1.0"));

			requireThat(result.status(), "status").withContext(result.output(), "output").isEqualTo(0);
			List<String> commands = fixture.commands();
			requireThat(commands, "commands").contains("download " + RELEASE_NAME + ".tar.gz").
				contains("download SHA256SUMS.txt").contains("codex plugin add cat@cat").contains("plugin-info");
		}
	}

	/**
	 * Verifies that a release archive whose checksum does not match is rejected before Codex is called.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void rejectsReleaseWithWrongChecksum() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.release(fixture.artifact(), true);

			Result result = fixture.run(fixture.fakeDownloads() + install("-Source v1.0"));

			requireThat(result.status(), "status").isNotEqualTo(0);
			requireThat(result.output(), "output").contains("checksum");
			requireThat(fixture.commands(), "commands").doesNotContain("codex plugin add cat@cat");
			requireThat(Files.exists(fixture.codexHome.resolve("plugins/cat-marketplace")), "marketplace").isFalse();
		}
	}

	/**
	 * Verifies that an upgrade runs each pending migration in order, then records the new version.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void runsPendingMigrations() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path artifact = fixture.artifact();
			String record = "Add-Content -LiteralPath $env:COMMAND_LOG -Value " +
				"($env:CAT_MIGRATION_FROM_VERSION + '->' + $env:CAT_MIGRATION_TO_VERSION)";
			addMigration(artifact, "0.9", record);
			addMigration(artifact, "0.10", record);
			addMigration(artifact, "1.0", record);
			Path data = Files.createDirectories(fixture.codexHome.resolve("plugins/data/cat-cat"));
			Files.writeString(data.resolve("migration-version"), "0.9\n");

			Result result = fixture.run(install("-Source " + literal(artifact)));

			requireThat(result.status(), "status").withContext(result.output(), "output").isEqualTo(0);
			List<String> commands = fixture.commands();
			requireThat(commands.subList(0, 2), "migrations").isEqualTo(List.of("0.9->0.10", "0.9->1.0"));
			requireThat(Files.readString(data.resolve("migration-version")).strip(), "version").isEqualTo("1.0");
		}
	}

	/**
	 * Verifies that a failed migration stops the installation before Codex is called and keeps the recorded version.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void failedMigrationKeepsRecordedVersion() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path artifact = fixture.artifact();
			addMigration(artifact, "1.0", "throw 'The migration failed'");
			Path data = Files.createDirectories(fixture.codexHome.resolve("plugins/data/cat-cat"));
			Files.writeString(data.resolve("migration-version"), "0.9\n");

			Result result = fixture.run(install("-Source " + literal(artifact)));

			requireThat(result.status(), "status").isNotEqualTo(0);
			requireThat(result.output(), "output").contains("The migration failed");
			requireThat(Files.readString(data.resolve("migration-version")).strip(), "version").isEqualTo("0.9");
			requireThat(fixture.commands(), "commands").isEmpty();
		}
	}

	/**
	 * Verifies that the installer refuses to replace a custom agent that CAT did not install.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void keepsUnownedAgent() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path artifact = fixture.artifact();
			Path agent = Files.createDirectories(fixture.codexHome.resolve("agents")).resolve("cat-reviewer.toml");
			Files.writeString(agent, "name = \"user's reviewer\"\n");

			Result result = fixture.run(install("-Source " + literal(artifact)));

			requireThat(result.status(), "status").isNotEqualTo(0);
			requireThat(result.output(), "output").contains("unowned");
			requireThat(Files.readString(agent), "agent").isEqualTo("name = \"user's reviewer\"\n");
			requireThat(fixture.commands(), "commands").isEmpty();
		}
	}

	/**
	 * Verifies that installing an extracted artifact when Codex's {@code config.toml} cannot be written, as in an
	 * agent's sandbox, changes nothing, runs no command, and names the file and the command that repeats the
	 * installation outside the sandbox.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void installRefusesReadOnlyConfig() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path artifact = fixture.artifact();
			fixture.installed();
			Path config = fixture.codexHome.resolve("config.toml");
			Files.writeString(config, "[plugins]\n");
			Files.setAttribute(config, "dos:readonly", true);
			try
			{
				Map<String, String> before = snapshot(fixture.codexHome);

				Result result = fixture.run(install("-Source " + literal(artifact)));

				requireThat(result.status(), "status").withContext(result.output(), "output").isNotEqualTo(0);
				requireThat(result.output(), "output").
					contains("CAT changed nothing because it cannot write " + fullPath(config) + ".").
					contains("$env:CODEX_HOME = " + literal(fixture.codexHome) + "; & ([scriptblock]::Create((irm " +
						"https://raw.githubusercontent.com/holdmyspot-com/cat/v1.0/client/codex/lifecycle/src/main/" +
						"resources/install/install-codex.ps1))) -Source '").
					contains(RELEASE_NAME + "'");
				requireAgentToldToHandCommandToUser(result.output());
				requireThat(fixture.commands(), "commands").isEmpty();
				requireThat(snapshot(fixture.codexHome), "after").isEqualTo(before, "before");
			}
			finally
			{
				Files.setAttribute(config, "dos:readonly", false);
			}
		}
	}

	/**
	 * Verifies that installing with a relative marketplace path whose folders do not exist, when Codex's
	 * {@code config.toml} cannot be written, creates nothing in the working folder, runs no command, and names the
	 * file and the command, which repeats the marketplace path.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void relativeMarketplaceRefusalCreatesNothing() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path artifact = fixture.artifact();
			Path config = Files.createDirectories(fixture.codexHome).resolve("config.toml");
			Files.writeString(config, "[plugins]\n");
			Files.setAttribute(config, "dos:readonly", true);
			try
			{
				Map<String, String> before = snapshot(fixture.root);

				Result result = fixture.run("$env:CAT_CODEX_MARKETPLACE_ROOT = 'missing\\cat-marketplace'\n" +
					install("-Source " + literal(artifact)));

				requireThat(result.status(), "status").withContext(result.output(), "output").isNotEqualTo(0);
				requireThat(result.output(), "output").
					contains("CAT changed nothing because it cannot write " + fullPath(config) + ".").
					contains("$env:CODEX_HOME = " + literal(fixture.codexHome) +
						"; $env:CAT_CODEX_MARKETPLACE_ROOT = 'missing\\cat-marketplace'; & ([scriptblock]::Create(");
				requireThat(fixture.commands(), "commands").isEmpty();
				requireThat(Files.exists(fixture.root.resolve("missing")), "missing").isFalse();
				requireThat(snapshot(fixture.root), "after").isEqualTo(before, "before");
			}
			finally
			{
				Files.setAttribute(config, "dos:readonly", false);
			}
		}
	}

	/**
	 * Verifies that installing with a relative marketplace path whose folders do not exist creates the marketplace
	 * below the working folder, where Codex resolves the path.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void installsIntoRelativeMarketplace() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path artifact = fixture.artifact();

			Result result = fixture.run("$env:CAT_CODEX_MARKETPLACE_ROOT = 'missing\\cat-marketplace'\n" +
				install("-Source " + literal(artifact)));

			requireThat(result.status(), "status").withContext(result.output(), "output").isEqualTo(0);
			Path marketplace = fixture.root.resolve("missing/cat-marketplace");
			requireThat(marketplace.resolve(".agents/plugins/marketplace.json"), "marketplace").isRegularFile();
			requireThat(fixture.commands(), "commands").contains("codex plugin add cat@cat");
		}
	}

	/**
	 * Verifies that installing when the Codex home is on a drive that does not exist changes nothing, runs no command,
	 * and names the drive's root, which has no folder above it, and the command that repeats the installation.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void installRefusesMissingDrive() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path artifact = fixture.artifact();
			String root = missingDriveRoot();
			String codexHome = root + "codex";
			Map<String, String> before = snapshot(fixture.root);

			Result result = fixture.run("$env:CODEX_HOME = " + literal(codexHome) + "\n" +
				install("-Source " + literal(artifact)));

			requireThat(result.status(), "status").withContext(result.output(), "output").isNotEqualTo(0);
			requireThat(result.output(), "output").
				contains("CAT changed nothing because it cannot write " + root + ".").
				contains("  $env:CODEX_HOME = " + literal(codexHome) + "; & ([scriptblock]::Create((irm ");
			requireThat(fixture.commands(), "commands").isEmpty();
			requireThat(snapshot(fixture.root), "after").isEqualTo(before, "before");
		}
	}

	/**
	 * Verifies that removing the plugin when the Codex home is on a drive that does not exist changes nothing, runs no
	 * command, and names the drive's root, which has no folder above it, and the command that repeats the removal.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void uninstallRefusesMissingDrive() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path profile = Files.createDirectories(fixture.root.resolve("profile"));
			Files.createDirectories(profile.resolve(".cat/bundles/cat-claude-v1.0-windows-x64/bin"));
			String root = missingDriveRoot();
			String codexHome = root + "codex";
			Map<String, String> before = snapshot(fixture.root);

			Result result = fixture.run("$env:USERPROFILE = " + literal(profile) + "\n$env:CODEX_HOME = " +
				literal(codexHome) + "\n" + install("-Uninstall"));

			requireThat(result.status(), "status").withContext(result.output(), "output").isNotEqualTo(0);
			requireThat(result.output(), "output").
				contains("CAT changed nothing because it cannot write " + root + ".");
			requireThat(lines(result.output()), "outputLines").
				contains("  $env:CODEX_HOME = " + literal(codexHome) + "; & ([scriptblock]::Create((irm " +
					"https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/codex/lifecycle/src/main/" +
					"resources/install/bootstrap-codex.ps1))) -Uninstall");
			requireThat(fixture.commands(), "commands").isEmpty();
			requireThat(snapshot(fixture.root), "after").isEqualTo(before, "before");
		}
	}

	/**
	 * Verifies that removing an installed plugin deletes CAT's agents and marketplace, and keeps CAT's data.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void uninstallKeepsData() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path data = fixture.installed();

			Result result = fixture.run(install("-Uninstall"));

			requireThat(result.status(), "status").withContext(result.output(), "output").isEqualTo(0);
			requireThat(Files.exists(fixture.codexHome.resolve("agents/cat-reviewer.toml")), "agent").isFalse();
			requireThat(Files.exists(fixture.codexHome.resolve("plugins/cat-marketplace")), "marketplace").isFalse();
			requireThat(Files.isRegularFile(data.resolve("migration-version")), "data").isTrue();
			requireThat(fixture.commands(), "commands").contains("codex plugin remove cat@cat");
		}
	}

	/**
	 * Verifies that removing an installed plugin deletes the CAT artifacts that a runner skill downloaded into
	 * {@code .cat\bundles}, and keeps the rest of CAT's per-user folder.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void uninstallDeletesDownloadedBundles() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.installed();
			Path profile = Files.createDirectories(fixture.root.resolve("profile"));
			Path bundle = Files.createDirectories(profile.resolve(".cat/bundles/cat-claude-v1.0-windows-x64/bin"));
			Path rules = Files.createDirectories(profile.resolve(".cat/rules"));
			Files.writeString(rules.resolve("user.md"), "rule\n");

			Result result = fixture.run("$env:USERPROFILE = " + literal(profile) + "\n" + install("-Uninstall"));

			requireThat(result.status(), "status").withContext(result.output(), "output").isEqualTo(0);
			requireThat(Files.exists(bundle), "bundle").isFalse();
			requireThat(Files.exists(profile.resolve(".cat/bundles")), "bundles").isFalse();
			requireThat(Files.isRegularFile(rules.resolve("user.md")), "rule").isTrue();
		}
	}

	/**
	 * Verifies that removing an installed plugin with {@code -DeleteData} also deletes CAT's data.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void uninstallDeletesDataWhenAsked() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path data = fixture.installed();

			Result result = fixture.run(install("-Uninstall -DeleteData"));

			requireThat(result.status(), "status").withContext(result.output(), "output").isEqualTo(0);
			requireThat(Files.exists(data), "data").isFalse();
		}
	}

	/**
	 * Verifies that removing an installed plugin when Codex's {@code config.toml} cannot be written, as in an agent's
	 * sandbox, changes nothing, runs no command, and names the file and the command that repeats the removal outside
	 * the sandbox.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void uninstallRefusesReadOnlyConfig() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.installed();
			Path profile = Files.createDirectories(fixture.root.resolve("profile"));
			Files.createDirectories(profile.resolve(".cat/bundles/cat-claude-v1.0-windows-x64/bin"));
			Path config = fixture.codexHome.resolve("config.toml");
			Files.writeString(config, "[plugins]\n");
			Files.setAttribute(config, "dos:readonly", true);
			try
			{
				Map<String, String> before = snapshot(fixture.root);

				Result result = fixture.run("$env:USERPROFILE = " + literal(profile) + "\n" + install("-Uninstall"));

				requireThat(result.status(), "status").withContext(result.output(), "output").isNotEqualTo(0);
				requireThat(result.output(), "output").
					contains("CAT changed nothing because it cannot write " + fullPath(config) + ".");
				requireThat(lines(result.output()), "outputLines").
					contains("  $env:CODEX_HOME = " + literal(fixture.codexHome) + "; & ([scriptblock]::Create((irm " +
						"https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/codex/lifecycle/src/main/" +
						"resources/install/bootstrap-codex.ps1))) -Uninstall");
				requireAgentToldToHandCommandToUser(result.output());
				requireThat(fixture.commands(), "commands").isEmpty();
				requireThat(snapshot(fixture.root), "after").isEqualTo(before, "before");
			}
			finally
			{
				Files.setAttribute(config, "dos:readonly", false);
			}
		}
	}

	/**
	 * Verifies that the bootstrap script runs the installer of the latest release, which it fetches from that
	 * release's tag, and asks it to install that tag. A stand-in installer records the tag that it receives;
	 * {@link #installsVerifiedRelease()} covers what the real installer does with a tag.
	 *
	 * @throws IOException          if the files cannot be written or the script cannot be run
	 * @throws InterruptedException if the thread is interrupted while the script runs
	 */
	@Test
	public void bootstrapRunsLatestReleaseInstaller() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			String fakeRestMethod = """
				function Invoke-RestMethod
				{
					param([string] $Uri, [switch] $UseBasicParsing)
					Add-Content -LiteralPath $env:COMMAND_LOG -Value "fetch $Uri"
					if ($Uri -like '*/releases/latest') { return [pscustomobject]@{ tag_name = 'v9.8' } }
					return 'param([string] $Source) Add-Content -LiteralPath $env:COMMAND_LOG -Value "install $Source"'
				}
				""";

			Result result = fixture.run(fakeRestMethod + "& ([scriptblock]::Create([IO.File]::ReadAllText(" +
				literal(INSTALL.resolve("bootstrap-codex.ps1")) + ")))\n");

			requireThat(result.status(), "status").withContext(result.output(), "output").isEqualTo(0);
			requireThat(fixture.commands(), "commands").isEqualTo(List.of(
				"fetch https://api.github.com/repos/holdmyspot-com/cat/releases/latest",
				"fetch https://raw.githubusercontent.com/holdmyspot-com/cat/v9.8/client/codex/lifecycle/src/main/resources/" +
					"install/install-codex.ps1",
				"install v9.8"));
		}
	}

	/**
	 * Returns the statement that runs the installer as a script block.
	 *
	 * @param arguments the installer's arguments
	 * @return the statement
	 */
	private static String install(String arguments)
	{
		return "& ([scriptblock]::Create([IO.File]::ReadAllText(" + literal(INSTALL.resolve("install-codex.ps1")) +
			"))) " + arguments + "\n";
	}

	/**
	 * Registers a migration in an artifact.
	 *
	 * @param artifact the artifact
	 * @param version  the version that the migration migrates to
	 * @param script   the PowerShell migration
	 * @throws IOException if the files cannot be written
	 */
	private static void addMigration(Path artifact, String version, String script) throws IOException
	{
		Path migrations = artifact.resolve("install/migrations");
		Files.writeString(migrations.resolve("registry.tsv"), version + "\t" + version + ".sh\n",
			StandardOpenOption.APPEND);
		Files.writeString(migrations.resolve(version + ".sh"), "exit 1\n");
		Files.writeString(migrations.resolve(version + ".ps1"), script + "\n");
	}

	/**
	 * Returns the files and folders below a folder, each with its contents, so that two snapshots are equal only when
	 * nothing below the folder was created, deleted or changed.
	 *
	 * @param folder the folder
	 * @return the path of each file and folder relative to {@code folder}, mapped to the file's contents encoded in
	 *   Base64, or to {@code "folder"} for a folder
	 * @throws IOException if the folder cannot be read
	 */
	private static Map<String, String> snapshot(Path folder) throws IOException
	{
		Map<String, String> snapshot = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(folder))
		{
			for (Path path : paths.toList())
			{
				String contents;
				if (Files.isDirectory(path))
					contents = "folder";
				else
					contents = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
				snapshot.put(folder.relativize(path).toString(), contents);
			}
		}
		return snapshot;
	}

	/**
	 * Returns the form of an existing path that the installer names when it cannot write the path. Windows PowerShell's
	 * {@code [IO.Path]::GetFullPath} expands a short 8.3 name, such as {@code RUNNER~1}, which the temporary folder
	 * that holds each test's files can contain, and does not follow links.
	 *
	 * @param existing the path of an existing file or folder
	 * @return the path with each short name replaced by its long name
	 * @throws IOException if the path does not exist or cannot be read
	 */
	private static Path fullPath(Path existing) throws IOException
	{
		return existing.toRealPath(LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * Returns the root of a drive that does not exist, the first one from {@code Z:\} down to {@code D:\} that is
	 * not among the file system's roots, which include mapped and substituted drives.
	 *
	 * @return the root, such as {@code Z:\}
	 * @throws SkipException if every drive from {@code D:} to {@code Z:} exists
	 */
	private static String missingDriveRoot()
	{
		Set<String> roots = new HashSet<>();
		for (File root : File.listRoots())
			roots.add(root.getPath().toUpperCase(Locale.ROOT));
		for (char letter = 'Z'; letter >= 'D'; letter = (char) (letter - 1))
		{
			String root = letter + ":\\";
			if (!roots.contains(root))
				return root;
		}
		throw new SkipException("Every drive from D: to Z: exists, so none can stand for a missing drive");
	}

	/**
	 * Verifies that a refusal's output tells an agent that ran the command to show the user the command, ask them to
	 * run it outside the sandbox and end its turn, and does not offer the Claude Code prompt prefix.
	 *
	 * @param output the output of the refused operation
	 */
	private static void requireAgentToldToHandCommandToUser(String output)
	{
		requireThat(output, "output").
			contains("If you are an agent that ran the command that printed this, show the user the command above " +
				"exactly as printed, ask them to run it outside the sandbox, and end your turn there.").
			doesNotContain("prefixed with");
	}

	/**
	 * Splits text into lines, whatever line terminator each uses.
	 *
	 * @param text the text to split
	 * @return the lines, without their terminators
	 */
	private static List<String> lines(String text)
	{
		return List.of(text.split("\\R", -1));
	}

	/**
	 * Returns a PowerShell string literal.
	 *
	 * @param value the string's value
	 * @return the literal
	 */
	private static String literal(Object value)
	{
		return "'" + value.toString().replace("'", "''") + "'";
	}

	/**
	 * The output and exit status of a script.
	 *
	 * @param status the exit status
	 * @param output standard output and standard error
	 */
	private record Result(int status, String output)
	{
	}

	/**
	 * The folders of one test: a Codex home, a folder that holds {@code codex.cmd}, and a log of the commands that the
	 * installer runs.
	 */
	private static final class Fixture
	{
		private final Path root;
		private final Path codexHome;
		private final Path bin;
		private final Path log;
		private final Path release;

		/**
		 * Creates the folders of a test.
		 *
		 * @param cleanup the folders to delete after the test
		 * @throws IOException if the folders cannot be created
		 */
		private Fixture(TemporaryDirectories cleanup) throws IOException
		{
			if (!Platform.current().isWindows())
				throw new SkipException("The PowerShell installer installs CAT only on Windows");
			this.root = cleanup.add(Files.createTempDirectory("codex installer-")).toAbsolutePath();
			this.codexHome = root.resolve("codex home");
			this.bin = Files.createDirectories(root.resolve("bin"));
			this.log = root.resolve("commands.log");
			this.release = Files.createDirectories(root.resolve("release"));
			Files.writeString(log, "");
			Files.writeString(bin.resolve("codex.cmd"), "@echo codex %*>>\"%COMMAND_LOG%\"\r\n");
		}

		/**
		 * Creates an extracted artifact of the Codex plugin.
		 *
		 * @return the artifact's folder
		 * @throws IOException if the files cannot be written
		 */
		private Path artifact() throws IOException
		{
			Path artifact = root.resolve(RELEASE_NAME);
			Files.createDirectories(artifact.resolve(".codex-plugin"));
			Files.writeString(artifact.resolve(".codex-plugin/plugin.json"), "{}\n");
			Files.createDirectories(artifact.resolve("bin"));
			Files.createDirectories(artifact.resolve("client"));
			Files.writeString(artifact.resolve("client/VERSION"), "1.0\n");
			Files.writeString(artifact.resolve("bin/plugin-info.cmd"),
				"@echo plugin-info>>\"%COMMAND_LOG%\"\r\n");
			Files.createDirectories(artifact.resolve("install/migrations"));
			Files.writeString(artifact.resolve("install/migrations/registry.tsv"),
				"# target-version<TAB>script-relative-to-this-directory\n");
			Files.writeString(artifact.resolve("install/codex-marketplace.json"), "{\"name\": \"cat\"}\n");
			Files.createDirectories(artifact.resolve("agents/codex"));
			Files.writeString(artifact.resolve("agents/codex/reviewer.toml"), "name = \"cat-reviewer\"\n");
			return artifact;
		}

		/**
		 * Creates the files that installing an artifact leaves in the Codex home: CAT's marketplace, its custom agent
		 * with the list of agents that CAT owns, and its data version, as
		 * {@link CodexWindowsInstallerTest#installsExtractedArtifact()} shows.
		 *
		 * @return CAT's data folder
		 * @throws IOException if the files cannot be written
		 */
		private Path installed() throws IOException
		{
			Path plugin = Files.createDirectories(codexHome.resolve("plugins/cat-marketplace/plugins/cat/.codex-plugin"));
			Files.writeString(plugin.resolve("plugin.json"), "{}\n");
			Path agents = Files.createDirectories(codexHome.resolve("agents"));
			Files.writeString(agents.resolve("cat-reviewer.toml"), "name = \"cat-reviewer\"\n");
			Path data = Files.createDirectories(codexHome.resolve("plugins/data/cat-cat"));
			Files.writeString(data.resolve("codex-agent-files"), "cat-reviewer.toml\n");
			Files.writeString(data.resolve("migration-version"), "1.0\n");
			return data;
		}

		/**
		 * Publishes an artifact as the Windows release archive with its checksum list.
		 *
		 * @param artifact      the artifact
		 * @param wrongChecksum {@code true} to publish a checksum that does not match the archive
		 * @throws IOException if the files cannot be written
		 */
		private void release(Path artifact, boolean wrongChecksum) throws IOException
		{
			Path archive = release.resolve(RELEASE_NAME + ".tar.gz");
			try (OutputStream out = Files.newOutputStream(archive);
				TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(out));
				Stream<Path> files = Files.walk(artifact))
			{
				tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
				for (Path file : files.toList())
				{
					String name = RELEASE_NAME + "/" + artifact.relativize(file).toString().replace('\\', '/');
					TarArchiveEntry entry = new TarArchiveEntry(file, name);
					tar.putArchiveEntry(entry);
					if (Files.isRegularFile(file))
						Files.copy(file, tar);
					tar.closeArchiveEntry();
				}
			}
			String checksum;
			if (wrongChecksum)
				checksum = "0".repeat(64);
			else
				checksum = sha256(archive);
			Files.writeString(release.resolve("SHA256SUMS.txt"), checksum + "  " + RELEASE_NAME + ".tar.gz\n");
		}

		/**
		 * Returns the SHA-256 checksum of a file.
		 *
		 * @param file the file
		 * @return the checksum in lowercase hexadecimal
		 * @throws IOException if the file cannot be read
		 */
		private static String sha256(Path file) throws IOException
		{
			try
			{
				return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
			}
			catch (NoSuchAlgorithmException e)
			{
				throw new AssertionError(e);
			}
		}

		/**
		 * Returns a function that replaces {@code Invoke-WebRequest}, copying each file from the release folder and
		 * logging its name.
		 *
		 * @return the function's definition
		 */
		private String fakeDownloads()
		{
			return """
				function Invoke-WebRequest
				{
					param([string] $Uri, [string] $OutFile, [switch] $UseBasicParsing)
					$name = $Uri.Substring($Uri.LastIndexOf('/') + 1)
					Add-Content -LiteralPath $env:COMMAND_LOG -Value "download $name"
					Copy-Item -LiteralPath (Join-Path %s $name) -Destination $OutFile
				}
				""".formatted(literal(release));
		}

		/**
		 * Returns the commands that the installer ran, in order.
		 *
		 * @return the commands
		 * @throws IOException if the log cannot be read
		 */
		private List<String> commands() throws IOException
		{
			List<String> commands = new ArrayList<>();
			for (String line : Files.readAllLines(log, UTF_8))
			{
				if (!line.isBlank())
					commands.add(line.strip());
			}
			return commands;
		}

		/**
		 * Runs PowerShell statements in Windows PowerShell, in the test's root folder, with {@code codex.cmd} on the
		 * {@code PATH}.
		 *
		 * @param statements the statements
		 * @return the output and exit status
		 * @throws IOException          if PowerShell cannot be run
		 * @throws InterruptedException if the thread is interrupted while PowerShell runs
		 */
		private Result run(String statements) throws IOException, InterruptedException
		{
			Files.writeString(log, "");
			String script = "$ErrorActionPreference = 'Stop'\n" +
				"$env:COMMAND_LOG = " + literal(log) + "\n" +
				"$env:CODEX_HOME = " + literal(codexHome) + "\n" +
				"$env:PATH = " + literal(bin) + " + ';' + $env:PATH\n" +
				"try\n{\n" + statements + "}\ncatch\n{\n" +
				"\t[Console]::Out.WriteLine('error: ' + $_.Exception.Message)\n\texit 1\n}\n";
			String encoded = Base64.getEncoder().encodeToString(script.getBytes(UTF_16LE));
			ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
				"-EncodedCommand", encoded).directory(root.toFile()).redirectErrorStream(true);
			try (Process process = builder.start())
			{
				process.getOutputStream().close();
				String output = new String(process.getInputStream().readAllBytes(), UTF_8);
				return new Result(process.waitFor(), output);
			}
		}
	}
}
