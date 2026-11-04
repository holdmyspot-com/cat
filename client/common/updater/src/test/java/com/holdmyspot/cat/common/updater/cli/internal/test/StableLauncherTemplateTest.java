package com.holdmyspot.cat.common.updater.cli.internal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests the Windows stable launcher template, which only Windows PowerShell can run, by the order of its steps after a
 * complete uninstall, during a runtime replacement and before it starts the updater.
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
	 * The step that moves the runtime that an operation staged into place.
	 */
	private static final String STAGED_MOVE = "[IO.Directory]::Move($swap.Staged, $runtime)";
	/**
	 * The step that runs the new runtime's {@code java -version}.
	 */
	private static final String RUNTIME_CHECK = "$check.Arguments = '-version'";
	/**
	 * The step that restores the replaced runtime when the new one does not run.
	 */
	private static final String FAILED_CHECK = """
			if (-not $verified)
			{
				Restore-Runtime $swap
		""";
	/**
	 * The step that selects the operation's updater version.
	 */
	private static final String SELECTION = "[IO.File]::Replace($selection, $currentPath, $null)";
	/**
	 * The step that prints the result that the updater saved before the runtime replacement.
	 */
	private static final String SWAP_OUTPUT = "$swapStream = [Console]::OpenStandardOutput()";
	/**
	 * The step that puts back a runtime that a stopped launcher moved aside.
	 */
	private static final String REPAIR = "[IO.Directory]::Move($pending.Kept, $runtime)";
	/**
	 * The check that ends the launcher when the shared runtime has no Java.
	 */
	private static final String MISSING_JAVA = "if (-not (Test-Path -LiteralPath $java -PathType Leaf))";

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
		String template = template();

		int verified = template.indexOf(VERIFIED_DELETION);
		int removal = template.indexOf(BASE_REMOVAL);
		int output = template.indexOf(RESULT_OUTPUT);
		requireThat(verified, "verifiedDeletion").isNotNegative();
		requireThat(removal, "baseRemoval").isGreaterThan(verified);
		requireThat(output, "resultOutput").isGreaterThan(removal);
	}

	/**
	 * After the updater hands over a runtime replacement, the Windows stable launcher moves the staged runtime into
	 * place, runs its {@code java -version}, restores the replaced runtime when that fails, and only after the check
	 * selects the new updater version and then prints the saved result.
	 *
	 * @throws IOException if the template cannot be read
	 */
	@Test
	public void selectsVersionAfterRuntimeRuns() throws IOException
	{
		String template = template();

		int moved = template.indexOf(STAGED_MOVE);
		int checked = template.indexOf(RUNTIME_CHECK);
		int restored = template.indexOf(FAILED_CHECK);
		int selected = template.indexOf(SELECTION);
		int printed = template.indexOf(SWAP_OUTPUT);
		requireThat(moved, "stagedMove").isNotNegative();
		requireThat(checked, "runtimeCheck").isGreaterThan(moved);
		requireThat(restored, "failedCheck").isGreaterThan(checked);
		requireThat(selected, "selection").isGreaterThan(restored);
		requireThat(printed, "swapOutput").isGreaterThan(selected);
	}

	/**
	 * Before it requires the shared runtime's Java, the Windows stable launcher puts back a runtime that a launcher
	 * which stopped during a runtime replacement had moved aside.
	 *
	 * @throws IOException if the template cannot be read
	 */
	@Test
	public void repairsRuntimeBeforeRequiringIt() throws IOException
	{
		String template = template();

		int repaired = template.indexOf(REPAIR);
		int required = template.indexOf(MISSING_JAVA);
		requireThat(repaired, "repair").isNotNegative();
		requireThat(required, "missingJava").isGreaterThan(repaired);
	}

	/**
	 * Reads the Windows stable launcher template.
	 *
	 * @return the template
	 * @throws IOException if the template cannot be read
	 */
	private static String template() throws IOException
	{
		return Files.readString(moduleFolder().resolve(
			"src/main/resources/com/holdmyspot/cat/common/updater/cli/stable-launcher.ps1"));
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
