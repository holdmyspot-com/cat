package com.holdmyspot.cat.common.installer.engine.test;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.contract.test.PluginActivationContract;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.testng.annotations.Test;

/**
 * Runs the {@link PluginActivationContract} against {@link RegisteredProvider}, with {@link RecordingIntegration}
 * installing each version, so that the generic harness of the install tests reads its installation as the Claude Code
 * and Codex activations do.
 */
public final class RegisteredProviderContractTest
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
				return new RegisteredProvider();
			}

			@Override
			public HarnessInstallation installation(Path home)
			{
				return RecordingIntegration.layout("harness-a", home);
			}

			@Override
			public void install(Path home, PluginVersion version) throws IOException
			{
				RecordingIntegration integration = new RecordingIntegration();
				Path stated = integration.marketplaceFolder(home, version).resolve(PluginDirectory.VERSION);
				Files.createDirectories(stated.getParent());
				Files.writeString(stated, version + "\n");
				integration.register(home, version, RecordingIntegration.cli(), new FileReplacer(Platform.current(),
					() ->
					{
					}, Clock.systemUTC()));
			}

			@Override
			public void uninstall(Path home) throws IOException
			{
				new RecordingIntegration().unregister(home, RecordingIntegration.cli(), true);
			}
		});

	/**
	 * Creates the test.
	 */
	public RegisteredProviderContractTest()
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
			CONTRACT.freshHomeHasNoVersion(cleanup.add(Files.createTempDirectory("cat-activation-").toRealPath()));
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
			CONTRACT.readsInstalledVersion(cleanup.add(Files.createTempDirectory("cat-activation-").toRealPath()));
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
			CONTRACT.missingVersionFolderIsInconsistent(
				cleanup.add(Files.createTempDirectory("cat-activation-").toRealPath()));
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
			CONTRACT.leftoverMarketplaceIsInconsistent(
				cleanup.add(Files.createTempDirectory("cat-activation-").toRealPath()));
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
			CONTRACT.keepsDataInDataDirectory(cleanup.add(Files.createTempDirectory("cat-activation-").toRealPath()));
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
			CONTRACT.removalLeavesNoInstallation(
				cleanup.add(Files.createTempDirectory("cat-activation-").toRealPath()));
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
			CONTRACT.installsCompanionsAgain(cleanup.add(Files.createTempDirectory("cat-activation-").toRealPath()));
		}
	}
}
