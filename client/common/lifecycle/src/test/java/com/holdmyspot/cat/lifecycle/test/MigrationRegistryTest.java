package com.holdmyspot.cat.lifecycle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.engine.StagedRelease;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests that the release's migration registry makes the updater of the installation that every harness shared, which
 * release 1.0 published, refuse every later release as a routine update, while this release's updater accepts the
 * routine updates of the installations that its setup made.
 */
public final class MigrationRegistryTest
{
	/**
	 * The release's migration registry, found from this module's {@code target/test-classes} folder.
	 */
	private static final Path REGISTRY = moduleFolder().resolve("src/main/resources/install/migrations/registry.tsv");
	/**
	 * The migration version that the updater of the shared installation recorded for each harness it activated
	 * release 1.0 in.
	 */
	private static final String PUBLISHED = "1.0";

	/**
	 * Returns this module's folder.
	 *
	 * @return the folder
	 */
	private static Path moduleFolder()
	{
		try
		{
			Path classes = Path.of(MigrationRegistryTest.class.getProtectionDomain().getCodeSource().getLocation().
				toURI());
			// target/test-classes is two levels below the module
			return classes.resolve("../..").normalize();
		}
		catch (URISyntaxException e)
		{
			throw new AssertionError(e);
		}
	}

	/** Creates a test instance. */
	public MigrationRegistryTest()
	{
	}

	/**
	 * Supplies releases after release 1.0.
	 *
	 * @return the release version
	 */
	@DataProvider
	public Object[][] laterReleases()
	{
		return new Object[][]{{"1.0.0"}, {"1.0.1"}, {"1.1"}, {"2.0"}};
	}

	/**
	 * The updater of the shared installation refuses a later release as a routine update.
	 *
	 * @param release the later release
	 * @throws IOException if the registry cannot be read
	 */
	@Test(dataProvider = "laterReleases")
	public void sharedUpdaterRefusesLaterRelease(String release) throws IOException
	{
		requireThat(SharedUpdater.refuses(Files.readAllLines(REGISTRY, UTF_8), PUBLISHED, release), "refuses").
			isTrue();
	}

	/**
	 * The updater of the shared installation still accepts release 1.0 itself, which it reports as up to date.
	 *
	 * @throws IOException if the registry cannot be read
	 */
	@Test
	public void sharedUpdaterAcceptsReleaseOneZero() throws IOException
	{
		requireThat(SharedUpdater.refuses(Files.readAllLines(REGISTRY, UTF_8), PUBLISHED, PUBLISHED), "refuses").
			isFalse();
	}

	/**
	 * This updater accepts a routine update to a later release in a harness where it activated release 1.0.
	 *
	 * @param release the later release
	 * @throws IOException    if the fixture cannot be written
	 * @throws TrustException if the update is refused
	 */
	@Test(dataProvider = "laterReleases")
	public void updaterAcceptsLaterRelease(String release) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(Files.createTempDirectory("cat-migration-registry-"));
			Files.createDirectories(tree.resolve("install/migrations"));
			Files.copy(REGISTRY, tree.resolve("install/migrations/registry.tsv"));
			Path marker = tree.resolve("migration-version");
			Files.writeString(marker, StagedRelease.migrationVersion(PUBLISHED) + "\n", UTF_8);

			StagedRelease.requireNoPendingMigration(tree, marker, PUBLISHED, release);
		}
	}

	/**
	 * This updater's setup accepts a later release in a harness where the updater of the shared installation activated
	 * release 1.0.
	 *
	 * @param release the later release
	 * @throws IOException    if the fixture cannot be written
	 * @throws TrustException if setup is refused
	 */
	@Test(dataProvider = "laterReleases")
	public void setupAcceptsLaterRelease(String release) throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(Files.createTempDirectory("cat-migration-setup-"));
			Files.createDirectories(tree.resolve("install/migrations"));
			Files.copy(REGISTRY, tree.resolve("install/migrations/registry.tsv"));
			Path marker = tree.resolve("migration-version");
			Files.writeString(marker, PUBLISHED + "\n", UTF_8);

			StagedRelease.requireNoPendingSetupMigration(tree, marker, PUBLISHED, release);
		}
	}

	/**
	 * Repeats the routine update check of the updater that release 1.0 published (commit {@code 6a17ae364a}), which
	 * ran from the installation that every harness shared. That updater refuses a release whose registry names a
	 * migration after the recorded version, up to the release's version. Versions compare number by number, and a
	 * version that agrees on every number of a shorter one sorts after it.
	 * <p>
	 * The copy keeps the published behaviour fixed when this release's updater changes.
	 */
	private static final class SharedUpdater
	{
		/**
		 * Returns whether the updater refuses a release.
		 *
		 * @param registry the release's registry lines
		 * @param recorded the harness's recorded migration version
		 * @param release  the release version
		 * @return true if a migration is pending
		 */
		static boolean refuses(List<String> registry, String recorded, String release)
		{
			for (String line : registry)
			{
				if (line.isBlank() || line.startsWith("#"))
					continue;
				String target = line.split("\t", -1)[0];
				if (compare(target, recorded) > 0 && compare(target, release) <= 0)
					return true;
			}
			return false;
		}

		/**
		 * Compares two versions.
		 *
		 * @param first  a version
		 * @param second another version
		 * @return a negative number, zero or a positive number as {@code first} is lower than, equal to or higher than
		 * 	{@code second}
		 */
		private static int compare(String first, String second)
		{
			List<Integer> left = numbers(first);
			List<Integer> right = numbers(second);
			for (int index = 0; index < Math.min(left.size(), right.size()); ++index)
			{
				int difference = Integer.compare(left.get(index), right.get(index));
				if (difference != 0)
					return difference;
			}
			return Integer.compare(left.size(), right.size());
		}

		/**
		 * Returns a version's numbers.
		 *
		 * @param version the version
		 * @return its numbers, most significant first
		 */
		private static List<Integer> numbers(String version)
		{
			List<Integer> numbers = new ArrayList<>();
			for (String number : version.split("\\.", -1))
				numbers.add(Integer.valueOf(number));
			return numbers;
		}

		/**
		 * Prevents instantiation.
		 */
		private SharedUpdater()
		{
		}
	}
}
