package com.holdmyspot.cat.common.updater.cli.internal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests the Windows stable launcher template, which only Windows PowerShell can run, by the order of its steps after a
 * complete uninstall.
 */
public final class StableLauncherTemplateTest
{
	/**
	 * The check that ends the launcher with not_removed while the data directory still exists.
	 */
	private static final String VERIFIED_DELETION = """
		if (Test-Path -LiteralPath $data)
		{
			Exit-NotRemoved
		}
		""";
	/**
	 * The best-effort removal of the directory that holds the data directory, which a non-recursive delete performs only
	 * when it is empty.
	 */
	private static final String BASE_REMOVAL = """
		try
		{
			[IO.Directory]::Delete((Split-Path -Parent $data), $false)
		}
		catch
		{
		}
		""";
	/**
	 * The step that prints the saved uninstall result.
	 */
	private static final String RESULT_OUTPUT = "$output = [Console]::OpenStandardOutput()";

	/**
	 * Creates the test.
	 */
	public StableLauncherTemplateTest()
	{
	}

	/**
	 * After it has verified that the data directory is gone, the Windows stable launcher removes the directory that
	 * held it when that is now empty, ignoring a failure, and only then prints the saved result.
	 *
	 * @throws IOException if the template cannot be read
	 */
	@Test
	public void removesEmptyBaseAfterDeletion() throws IOException
	{
		String template = Files.readString(moduleFolder().resolve(
			"src/main/resources/com/holdmyspot/cat/common/updater/cli/stable-launcher.ps1"));

		int verified = template.indexOf(VERIFIED_DELETION);
		int removal = template.indexOf(BASE_REMOVAL);
		int output = template.indexOf(RESULT_OUTPUT);
		requireThat(verified, "verifiedDeletion").isNotNegative();
		requireThat(removal, "baseRemoval").isGreaterThan(verified);
		requireThat(output, "resultOutput").isGreaterThan(removal);
	}

	/**
	 * Returns this module's folder.
	 *
	 * @return the folder
	 */
	private static Path moduleFolder()
	{
		try
		{
			Path classes = Path.of(StableLauncherTemplateTest.class.getProtectionDomain().getCodeSource().getLocation().
				toURI());
			// target/test-classes is two levels below the module
			return classes.resolve("../..").normalize();
		}
		catch (URISyntaxException e)
		{
			throw new AssertionError(e);
		}
	}
}
