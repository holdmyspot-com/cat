package com.holdmyspot.cat.claude.installer.test;

import com.holdmyspot.cat.claude.installer.Activation;
import com.holdmyspot.cat.claude.installer.Integration;
import com.holdmyspot.cat.claude.layout.ClaudeInstallation;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.contract.test.PluginActivationContract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.testng.annotations.Test;

/**
 * Runs the {@link PluginActivationContract} against {@link Activation}, with {@link Integration} installing each
 * version through Claude Code's program as {@link ClaudeProgram} plays it.
 */
public final class ClaudeActivationContractTest
{
	/**
	 * The contract over the activation.
	 */
	private static final PluginActivationContract CONTRACT = new PluginActivationContract(
		new PluginActivationContract.Subject()
		{
			@Override
			public PluginActivation activation()
			{
				return new Activation();
			}

			@Override
			public HarnessInstallation installation(Path home)
			{
				return new ClaudeInstallation(home);
			}

			@Override
			public void install(Path home, PluginVersion version) throws IOException
			{
				ClaudeProgram.writeVersionFolder(home, version);
				new Integration(Platform.current()).register(home, version, ClaudeProgram.cli(home),
					new FileReplacer(Platform.current(), () ->
					{
					}, Clock.systemUTC()));
			}

			@Override
			public void uninstall(Path home) throws IOException
			{
				new Integration(Platform.current()).unregister(home, ClaudeProgram.cli(home), true);
			}
		});

	/**
	 * Creates the test.
	 */
	public ClaudeActivationContractTest()
	{
	}

	/**
	 * Verifies that the harness identifier and plugin manifest have the documented form.
	 */
	@Test
	public void namesHarness()
	{
		CONTRACT.namesHarness();
	}

	/**
	 * Verifies that a home without CAT reports no version, consistently.
	 *
	 * @throws IOException if the home cannot be created or read
	 */
	@Test
	public void freshHomeHasNoVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.freshHomeHasNoVersion(cleanup.add(Files.createTempDirectory("cat-activation-")));
		}
	}

	/**
	 * Verifies that the version that the harness installed last is reported.
	 *
	 * @throws IOException if the home cannot be created, written or read
	 */
	@Test
	public void readsInstalledVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.readsInstalledVersion(cleanup.add(Files.createTempDirectory("cat-activation-")));
		}
	}

	/**
	 * Verifies that a deleted version folder makes the installation inconsistent.
	 *
	 * @throws IOException if the home cannot be created, written or read
	 */
	@Test
	public void missingVersionFolderIsInconsistent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.missingVersionFolderIsInconsistent(cleanup.add(Files.createTempDirectory("cat-activation-")));
		}
	}

	/**
	 * Verifies that a marketplace without an installed version is inconsistent.
	 *
	 * @throws IOException if the home cannot be created, written or read
	 */
	@Test
	public void leftoverMarketplaceIsInconsistent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.leftoverMarketplaceIsInconsistent(cleanup.add(Files.createTempDirectory("cat-activation-")));
		}
	}

	/**
	 * Verifies that the data version file lies in the installation's data directory.
	 *
	 * @throws IOException if the home cannot be created
	 */
	@Test
	public void keepsDataInDataDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.keepsDataInDataDirectory(cleanup.add(Files.createTempDirectory("cat-activation-")));
		}
	}

	/**
	 * Verifies that deleting the removal paths leaves no installation and keeps the data.
	 *
	 * @throws IOException if the home cannot be created, written or read
	 */
	@Test
	public void removalLeavesNoInstallation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.removalLeavesNoInstallation(cleanup.add(Files.createTempDirectory("cat-activation-")));
		}
	}

	/**
	 * Verifies that companion files can be installed again.
	 *
	 * @throws IOException if the home cannot be created, written or read
	 */
	@Test
	public void installsCompanionsAgain() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.installsCompanionsAgain(cleanup.add(Files.createTempDirectory("cat-activation-")));
		}
	}
}
