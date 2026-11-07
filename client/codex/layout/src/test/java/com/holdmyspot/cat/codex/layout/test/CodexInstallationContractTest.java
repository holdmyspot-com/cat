package com.holdmyspot.cat.codex.layout.test;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installation.contract.test.HarnessInstallationContract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Runs the {@link HarnessInstallationContract} against {@link CodexInstallation}, where Codex keeps no copies of a
 * version beside the version folders.
 */
public final class CodexInstallationContractTest
{
	/**
	 * The contract over the installation.
	 */
	private static final HarnessInstallationContract CONTRACT = new HarnessInstallationContract(
		new HarnessInstallationContract.Subject()
		{
			@Override
			public HarnessInstallation installation(Path home)
			{
				return new CodexInstallation(home);
			}

			@Override
			public String copyName(HarnessInstallation installation, Path folder, PluginVersion version)
			{
				throw new AssertionError("Codex keeps no copies: " + folder);
			}
		});

	/**
	 * Creates the test.
	 */
	public CodexInstallationContractTest()
	{
	}

	/**
	 * Verifies that the installation's home is the normalized directory that it was created for.
	 *
	 * @throws IOException if the temporary home cannot be created
	 */
	@Test
	public void keepsNormalizedHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.keepsNormalizedHome(cleanup.add(Files.createTempDirectory("cat-codex-")));
		}
	}

	/**
	 * Verifies that every folder lies inside the home.
	 *
	 * @throws IOException if the temporary home cannot be created
	 */
	@Test
	public void placesFoldersInsideHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.placesFoldersInsideHome(cleanup.add(Files.createTempDirectory("cat-codex-")));
		}
	}

	/**
	 * Verifies that a version folder is named after its version.
	 *
	 * @throws IOException if the temporary home cannot be created
	 */
	@Test
	public void namesVersionFolders() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.namesVersionFolders(cleanup.add(Files.createTempDirectory("cat-codex-")));
		}
	}

	/**
	 * Verifies that the harness's own copies of a version are read back as that version.
	 *
	 * @throws IOException if the temporary home cannot be created
	 */
	@Test
	public void readsHarnessCopies() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.readsHarnessCopies(cleanup.add(Files.createTempDirectory("cat-codex-")));
		}
	}

	/**
	 * Verifies that the data directory lies outside the version folders and holds the installer's files.
	 *
	 * @throws IOException if the temporary home cannot be created
	 */
	@Test
	public void keepsDataApart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.keepsDataApart(cleanup.add(Files.createTempDirectory("cat-codex-")));
		}
	}
}
