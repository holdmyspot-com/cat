package com.holdmyspot.cat.lifecycle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.engine.StagedRelease;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests that the installer accepts the release's migration registry, and that every migration it registers ships with
 * its scripts.
 */
public final class MigrationRegistryTest
{
	/**
	 * The release's migration directory, found from this module's {@code target/test-classes} folder.
	 */
	private static final Path MIGRATIONS = moduleFolder().resolve("src/main/resources/install/migrations");

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

	/**
	 * Creates a test instance.
	 */
	public MigrationRegistryTest()
	{
	}

	/**
	 * The installer reads the release's registry without refusing it, and every migration that it reads there has its
	 * POSIX script and its PowerShell script in the release's migration directory.
	 *
	 * @throws IOException    if the fixture cannot be written
	 * @throws TrustException if the installer refuses the registry
	 */
	@Test
	public void installerReadsRegistry() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path tree = cleanup.add(Files.createTempDirectory("cat-migration-registry-"));
			Files.createDirectories(tree.resolve("install/migrations"));
			Files.copy(MIGRATIONS.resolve("registry.tsv"), tree.resolve("install/migrations/registry.tsv"));

			List<String> scripts = StagedRelease.migrationsAfter(tree, ReleaseVersion.parse("0.0"),
				ReleaseVersion.parse("999999999.0"));

			for (String script : scripts)
			{
				requireThat(Files.isRegularFile(MIGRATIONS.resolve(script)), script).isTrue();
				String powerShell = script.substring(0, script.length() - ".sh".length()) + ".ps1";
				requireThat(Files.isRegularFile(MIGRATIONS.resolve(powerShell)), powerShell).isTrue();
			}
		}
	}
}
