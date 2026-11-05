package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.engine.DataMigration;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Install brings CAT's data to the data structures of the release that it installs by running the data migrations
 * that the release registers after the recorded data version.
 */
public final class MigrationTest
{
	/**
	 * The migration registry of a release that registers no data migration.
	 */
	private static final String NO_MIGRATION = "# No migration\n";
	/**
	 * The migration registry of release 1.3: one migration that release 1.0 already expects, two that change its data
	 * after 1.0, and one that a later release expects.
	 */
	private static final String REGISTRY = "1.0\tbase.sh\n1.1\tsplit.sh\n1.2\tmerge.sh\n1.4\tfuture.sh\n";

	/**
	 * Creates the tests.
	 */
	public MigrationTest()
	{
	}

	/**
	 * An install of a newer release runs, in registry order and from the newer release's version folder, the
	 * migrations after the recorded data version up to the newer release, telling each script the data directory and
	 * the versions it migrates between, and then records the newer release as the data version.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void runsMigrationsAfterRecordedVersion() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("migration-runs-")),
				new ReleaseCatalog("1.0", NO_MIGRATION).add("1.3", REGISTRY));
			fixture.installActive("1.0");

			UpdateResult result = fixture.install("v1.3");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.UPDATED);
			List<RecordingMigrations.Run> runs = fixture.migrations.runs();
			requireThat(runs.stream().map(RecordingMigrations.Run::summary).toList(), "runs").
				isEqualTo(List.of("split.sh 1.0 1.1", "merge.sh 1.1 1.2"));
			Path scripts = fixture.versionFolder("1.3").resolve("install/migrations");
			Path dataDirectory = fixture.migrationVersion().getParent();
			for (RecordingMigrations.Run run : runs)
			{
				requireThat(run.script().getParent(), "scriptDirectory").isEqualTo(scripts);
				requireThat(run.variables().get("CAT_MIGRATION_DATA_DIR"), "dataDirectory").
					isEqualTo(dataDirectory.toString());
			}
			requireThat(Files.readString(fixture.migrationVersion()), "dataVersion").isEqualTo("1.3\n");
		}
	}

	/**
	 * A first install has no earlier data to migrate, so it runs no migration and records its release as the data
	 * version.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void firstInstallRunsNoMigration() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("migration-first-")),
				new ReleaseCatalog("1.3", REGISTRY));

			fixture.installActive("1.3");

			requireThat(fixture.migrations.runs(), "runs").isEmpty();
			requireThat(Files.readString(fixture.migrationVersion()), "dataVersion").isEqualTo("1.3\n");
		}
	}

	/**
	 * Data that an uninstall kept records its data version, so installing CAT again migrates the data from that
	 * version even though the harness has no CAT version.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void migratesKeptData() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("migration-kept-")),
				new ReleaseCatalog("1.3", REGISTRY));
			Files.writeString(Files.createDirectories(fixture.migrationVersion().getParent()).
				resolve(fixture.migrationVersion().getFileName()), "1.1\n");

			UpdateResult result = fixture.install("v1.3");

			requireThat(result.result(), "result").withContext(result.message(), "message").
				isEqualTo(Result.INSTALLED);
			requireThat(fixture.migrations.runs().stream().map(RecordingMigrations.Run::summary).toList(), "runs").
				isEqualTo(List.of("merge.sh 1.1 1.2"));
			requireThat(Files.readString(fixture.migrationVersion()), "dataVersion").isEqualTo("1.3\n");
		}
	}

	/**
	 * The first migration that fails stops the install: later migrations do not run, the data version stays at the
	 * last migration that succeeded, and the message names the failed script and its output. Running the same install
	 * again resumes with the failed migration and completes it.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void stopsAtFailedMigration() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("migration-failed-")),
				new ReleaseCatalog("1.0", NO_MIGRATION).add("1.3", REGISTRY));
			fixture.installActive("1.0");
			fixture.migrations.fail("merge.sh", new CommandResult(3, "disk full\n"));

			UpdateResult failed = fixture.install("v1.3");

			requireThat(failed.result(), "failed").withContext(failed.message(), "message").isEqualTo(Result.FAILED);
			requireThat(failed.message(), "failedMessage").contains("merge.sh").contains("exit status 3").
				contains("disk full").contains("run the same install command again");
			requireThat(Files.readString(fixture.migrationVersion()), "failedDataVersion").isEqualTo("1.1\n");

			fixture.migrations.heal("merge.sh");
			UpdateResult rerun = fixture.install("v1.3");

			requireThat(rerun.result().exitStatus(), "rerunExitStatus").withContext(rerun.message(), "message").
				isEqualTo(0);
			requireThat(fixture.migrations.runs().stream().map(RecordingMigrations.Run::summary).toList(), "runs").
				isEqualTo(List.of("split.sh 1.0 1.1", "merge.sh 1.1 1.2", "merge.sh 1.1 1.2"));
			requireThat(Files.readString(fixture.migrationVersion()), "dataVersion").isEqualTo("1.3\n");
		}
	}

	/**
	 * A registered migration whose script the release does not ship fails the install without recording a newer
	 * data version, and the message names the script.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if the test policy is invalid
	 */
	@Test
	public void failsWithoutScript() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			EngineFixture fixture = new EngineFixture(cleanup.add(testDirectory("migration-missing-")),
				new ReleaseCatalog("1.0", NO_MIGRATION));
			fixture.installActive("1.0");
			fixture.catalog.addDefective("1.1", "missingScript");

			UpdateResult result = fixture.install("v1.1");

			requireThat(result.result(), "result").withContext(result.message(), "message").isEqualTo(Result.FAILED);
			requireThat(result.message(), "message").contains("migrate.sh");
			requireThat(fixture.migrations.runs(), "runs").isEmpty();
			requireThat(Files.readString(fixture.migrationVersion()), "dataVersion").isEqualTo("1.0\n");
		}
	}

	/**
	 * On Windows, each registered migration runs as the PowerShell script that the release ships beside it.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a version is malformed
	 */
	@Test
	public void runsPowerShellOnWindows() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("migration-windows-")).toRealPath();
			Path folder = root.resolve("plugin");
			Path scripts = Files.createDirectories(folder.resolve("install/migrations"));
			Files.writeString(scripts.resolve("registry.tsv"), "1.1\tsplit.sh\n");
			Files.writeString(scripts.resolve("split.sh"), "exit 0\n");
			Files.writeString(scripts.resolve("split.ps1"), "exit 0\n");
			Path data = Files.createDirectories(root.resolve("data"));
			Path versionFile = data.resolve("migration-version");
			RecordingMigrations runner = new RecordingMigrations();

			DataMigration.plan(folder, versionFile, ReleaseVersion.parse("1.1"),
				Optional.of(ReleaseVersion.parse("1.0"))).apply(folder, data, runner, true);

			requireThat(runner.runs().stream().map(RecordingMigrations.Run::script).toList(), "scripts").
				isEqualTo(List.of(scripts.resolve("split.ps1")));
			requireThat(Files.readString(versionFile), "dataVersion").isEqualTo("1.1\n");
		}
	}

	/**
	 * A registered script name that leaves the registry's directory is never run, and the data version stays.
	 *
	 * @throws IOException    if the fixture cannot be created or inspected
	 * @throws TrustException if a version is malformed
	 */
	@Test
	public void refusesScriptOutsideRegistry() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(testDirectory("migration-escape-")).toRealPath();
			Path folder = root.resolve("plugin");
			Path scripts = Files.createDirectories(folder.resolve("install/migrations"));
			Files.writeString(scripts.resolve("registry.tsv"), "1.1\t../../../escape.sh\n");
			Files.writeString(root.resolve("escape.sh"), "exit 0\n");
			Path data = Files.createDirectories(root.resolve("data"));
			Path versionFile = data.resolve("migration-version");
			Files.writeString(versionFile, "1.0\n");
			RecordingMigrations runner = new RecordingMigrations();
			DataMigration migration = DataMigration.plan(folder, versionFile, ReleaseVersion.parse("1.1"),
				Optional.empty());

			try
			{
				migration.apply(folder, data, runner, false);
				throw new AssertionError("A script outside the registry's directory ran");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("escape.sh");
			}
			requireThat(runner.runs(), "runs").isEmpty();
			requireThat(Files.readString(versionFile), "dataVersion").isEqualTo("1.0\n");
		}
	}
}
