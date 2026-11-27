package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.WindowsKnownFolders;
import java.io.IOException;
import java.nio.file.Path;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests {@link WindowsKnownFolders}, which only Windows can answer.
 */
public final class WindowsKnownFoldersTest
{
	/**
	 * Creates a test instance.
	 */
	public WindowsKnownFoldersTest()
	{
	}

	/**
	 * Verifies that Windows names an existing, absolute folder as the one that holds the program data that every user
	 * shares.
	 *
	 * @throws IOException   if Windows cannot be asked
	 * @throws SkipException if the platform is not Windows
	 */
	@Test
	public void namesTheProgramDataFolder() throws IOException
	{
		if (!Platform.current().isWindows())
			throw new SkipException("Only Windows can name its program data folder");

		Path folder = WindowsKnownFolders.programData();

		requireThat(folder, "folder").isAbsolute().isDirectory();
	}
}
