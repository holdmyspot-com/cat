package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.WindowsRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests {@link WindowsRegistry}, which only Windows can answer.
 */
public final class WindowsRegistryTest
{
	/**
	 * The key that every Windows machine has.
	 */
	private static final String VERSION_KEY = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion";
	/**
	 * The key that holds the folders of every Windows machine's user profiles, as expandable values.
	 */
	private static final String PROFILE_LIST_KEY = VERSION_KEY + "\\ProfileList";

	/**
	 * Creates a test instance.
	 */
	public WindowsRegistryTest()
	{
	}

	/**
	 * Verifies that a text value that exists is returned.
	 *
	 * @throws IOException   if Windows cannot be asked
	 * @throws SkipException if the platform is not Windows
	 */
	@Test
	public void readsTextValue() throws IOException
	{
		requireWindows();

		Optional<String> value = WindowsRegistry.machineText(VERSION_KEY, "SystemRoot");

		requireThat(value.isPresent(), "present").isTrue();
		requireThat(Path.of(value.get()), "folder").isAbsolute().isDirectory();
	}

	/**
	 * Verifies that an expandable text value is returned as stored, with its environment variables unexpanded, so
	 * that a value such as {@code %SystemDrive%\Users} is not an absolute path. Every Windows machine stores the
	 * folder of its user profiles as such a value.
	 *
	 * @throws IOException   if Windows cannot be asked
	 * @throws SkipException if the platform is not Windows
	 */
	@Test
	public void readsExpandableValueUnexpanded() throws IOException
	{
		requireWindows();

		Optional<String> value = WindowsRegistry.machineText(PROFILE_LIST_KEY, "ProfilesDirectory");

		requireThat(value.orElseThrow(), "value").startsWith("%SystemDrive%");
		requireThat(Path.of(value.orElseThrow()).isAbsolute(), "absolute").isFalse();
	}

	/**
	 * Verifies that a value that its key lacks, and a key that does not exist, give no value rather than a failure.
	 *
	 * @throws IOException   if Windows cannot be asked
	 * @throws SkipException if the platform is not Windows
	 */
	@Test
	public void givesNothingForAbsentValueOrKey() throws IOException
	{
		requireWindows();

		requireThat(WindowsRegistry.machineText(VERSION_KEY, "CatNoSuchValue"), "absentValue").
			isEqualTo(Optional.empty());
		requireThat(WindowsRegistry.machineText("SOFTWARE\\CatNoSuchKey\\Codex", "RequirementsPath"), "absentKey").
			isEqualTo(Optional.empty());
	}

	/**
	 * Verifies that a value that is not text is a failure, so that a caller cannot mistake it for an absent value.
	 *
	 * @throws IOException   if the read fails, as the test expects
	 * @throws SkipException if the platform is not Windows
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*CurrentMajorVersionNumber.*")
	public void refusesValueThatIsNotText() throws IOException
	{
		requireWindows();

		WindowsRegistry.machineText(VERSION_KEY, "CurrentMajorVersionNumber");
	}

	/**
	 * Requires Windows, whose registry these tests read.
	 *
	 * @throws SkipException if the current platform is not Windows
	 */
	private static void requireWindows()
	{
		if (!Platform.current().isWindows())
			throw new SkipException("Only Windows has a registry");
	}
}
