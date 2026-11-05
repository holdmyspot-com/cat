package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.engine.StagedRelease.Migration;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Brings CAT's data in one harness to the data structures of the release that install installs. The data version
 * file records the release whose data structures the data has. The data migrations that the release registers after
 * that version, up to and including the release itself, run in registry order from the release's version folder, and
 * the file records each migration's release once it succeeds, then the installed release.
 * <p>
 * Without a data version file, the active release's version stands for it; without either, the data is new and no
 * migration runs.
 */
public final class DataMigration
{
	/**
	 * The directory of the migration registry and its scripts, relative to a plugin folder.
	 */
	private static final String MIGRATIONS = "install/migrations";
	private final Path versionFile;
	private final ReleaseVersion release;
	private final ReleaseVersion recorded;
	private final List<Migration> pending;

	/**
	 * Creates the migration of one install.
	 *
	 * @param versionFile the data version file
	 * @param release     the release that install installs
	 * @param recorded    the data's version, or null when the data is new
	 * @param pending     the migrations to run, in order
	 */
	private DataMigration(Path versionFile, ReleaseVersion release, ReleaseVersion recorded, List<Migration> pending)
	{
		this.versionFile = versionFile;
		this.release = release;
		this.recorded = recorded;
		this.pending = List.copyOf(pending);
	}

	/**
	 * Finds the data migrations that installing a release runs, before install changes anything.
	 *
	 * @param tree        the release's validated plugin tree
	 * @param versionFile the data version file
	 * @param release     the release that the tree holds
	 * @param active      the harness's active release, if any
	 * @return the migration
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the data version file is reached through a symbolic link or cannot be read, or
	 *                              the registry cannot be read
	 * @throws TrustException       if the data version is not a release version, or the release's registry is absent
	 *                              or malformed while the data is older than the release
	 */
	public static DataMigration plan(Path tree, Path versionFile, ReleaseVersion release,
		Optional<ReleaseVersion> active) throws IOException, TrustException
	{
		requireThat(tree, "tree").isNotNull();
		requireThat(versionFile, "versionFile").isNotNull();
		requireThat(release, "release").isNotNull();
		requireThat(active, "active").isNotNull();
		StateFiles.requireNoLinks(versionFile);
		ReleaseVersion recorded = active.orElse(null);
		if (Files.exists(versionFile, NOFOLLOW_LINKS))
			recorded = ReleaseVersion.parse(StagedRelease.readVersion(versionFile));
		List<Migration> pending = List.of();
		if (recorded != null && recorded.compareTo(release) < 0)
			pending = StagedRelease.migrations(tree, recorded, release);
		return new DataMigration(versionFile, release, recorded, pending);
	}

	/**
	 * Runs the pending migrations from the release's version folder, recording the data version after each, and then
	 * records the release as the data version. The first migration that fails stops the others, and the data version
	 * stays at the last migration that succeeded.
	 *
	 * @param versionFolder the version folder into which the harness installed the release
	 * @param dataDirectory the directory of CAT's data in the harness, which the scripts change
	 * @param runner        runs the scripts
	 * @param windows       true to run each script's PowerShell counterpart
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a script is missing, cannot be run or fails, or the data version cannot be
	 *                              written; the message names the failed script
	 */
	public void apply(Path versionFolder, Path dataDirectory, MigrationRunner runner, boolean windows)
		throws IOException
	{
		requireThat(versionFolder, "versionFolder").isNotNull();
		requireThat(dataDirectory, "dataDirectory").isNotNull();
		requireThat(runner, "runner").isNotNull();
		ReleaseVersion from = recorded;
		for (Migration migration : pending)
		{
			Path script = script(versionFolder, migration.script(), windows);
			CommandResult result = runner.run(script, Map.of(
				"CAT_MIGRATION_DATA_DIR", dataDirectory.toString(),
				"CAT_MIGRATION_FROM_VERSION", from.toString(),
				"CAT_MIGRATION_TO_VERSION", migration.version().toString()));
			if (result.exitCode() != 0)
			{
				String output = result.output().strip();
				String detail = "";
				if (!output.isEmpty())
					detail = ": " + output;
				throw new IOException("The data migration " + migration.script() + " to release " +
					migration.version() + " failed with exit status " + result.exitCode() + detail);
			}
			record(migration.version());
			from = migration.version();
		}
		if (!release.equals(from))
			record(release);
	}

	/**
	 * Records a data version.
	 *
	 * @param version the version
	 * @throws IOException if the file cannot be written
	 */
	private void record(ReleaseVersion version) throws IOException
	{
		StateFiles.write(versionFile, (version + "\n").getBytes(UTF_8));
	}

	/**
	 * Locates a registered script in a version folder.
	 *
	 * @param versionFolder the version folder
	 * @param name          the script's registered name, relative to the registry's directory
	 * @param windows       true for the script's PowerShell counterpart
	 * @return the script
	 * @throws IOException if the name leaves the registry's directory, or the script is not a regular file
	 */
	private static Path script(Path versionFolder, String name, boolean windows) throws IOException
	{
		String file = name;
		if (windows)
		{
			if (!name.endsWith(".sh"))
				throw new IOException("The data migration " + name + " has no PowerShell counterpart.");
			file = name.substring(0, name.length() - ".sh".length()) + ".ps1";
		}
		Path directory = versionFolder.resolve(MIGRATIONS);
		Path script = directory.resolve(file).normalize();
		if (!script.startsWith(directory) || script.equals(directory))
			throw new IOException("The data migration " + name + " lies outside " + directory + ".");
		StateFiles.requireNoLinks(script);
		if (!Files.isRegularFile(script, NOFOLLOW_LINKS))
			throw new IOException("The data migration " + script + " is missing.");
		return script;
	}
}
