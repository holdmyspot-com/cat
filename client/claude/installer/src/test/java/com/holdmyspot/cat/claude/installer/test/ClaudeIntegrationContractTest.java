package com.holdmyspot.cat.claude.installer.test;

import com.holdmyspot.cat.claude.installer.Activation;
import com.holdmyspot.cat.claude.installer.Integration;
import com.holdmyspot.cat.claude.layout.ClaudeInstallation;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.contract.test.HarnessIntegrationContract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the {@link HarnessIntegrationContract} against {@link Integration}, with Claude Code's program as
 * {@link ClaudeProgram} plays it.
 */
public final class ClaudeIntegrationContractTest
{
	/**
	 * Writes JSON strings.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	/**
	 * The contract over the integration.
	 */
	private static final HarnessIntegrationContract CONTRACT = new HarnessIntegrationContract(
		new HarnessIntegrationContract.Subject()
		{
			@Override
			public HarnessIntegration integration()
			{
				return new Integration(Platform.current());
			}

			@Override
			public PluginActivation activation()
			{
				return new Activation();
			}

			@Override
			public HarnessCli cli(Path home)
			{
				return ClaudeProgram.cli(home);
			}

			@Override
			public void writePlugin(Path home, PluginVersion version) throws IOException
			{
				ClaudeProgram.writeVersionFolder(home, version);
			}

			@Override
			public CommandResult versionAnswer()
			{
				return ClaudeProgram.versionAnswer();
			}

			@Override
			public boolean deletesData()
			{
				return true;
			}

			@Override
			public boolean selectsByName()
			{
				return false;
			}

			@Override
			public void arrangeRunnerExceptions(HarnessIntegration integration, Path home) throws IOException
			{
				Path launcher = new ClaudeInstallation(home).marketplacePlugin().resolve("bin/claude-runner");
				Files.writeString(home.resolve("settings.json"), "{\"sandbox\":{\"excludedCommands\":[" +
					MAPPER.writeValueAsString(launcher + " *") + "]}}\n");
			}

			@Override
			public boolean arrangeConsent(HarnessIntegration integration, Path home)
			{
				// Claude Code's settings share no setting that CAT changes
				return false;
			}

			@Override
			public void changeConsentedEntry(HarnessIntegration integration, Path home)
			{
				throw new AssertionError("Claude Code has no consented entry");
			}

			@Override
			public boolean arrangeSharedSetting(HarnessIntegration integration, Path home)
			{
				return false;
			}
		});

	/**
	 * Creates the test.
	 */
	public ClaudeIntegrationContractTest()
	{
	}

	/**
	 * Verifies that the integration names its harness, product, program and agent session variable.
	 */
	@Test
	public void namesHarness()
	{
		CONTRACT.namesHarness();
	}

	/**
	 * Verifies that the installation at a home is that home's and that a default home lies in the user's home.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void placesInstallationAtHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.placesInstallationAtHome(
				cleanup.add(Files.createTempDirectory("cat-integration-")),
				cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}

	/**
	 * Verifies that the program environment recorded for a home selects that home.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void recordsProgramHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.recordsProgramHome(
				cleanup.add(Files.createTempDirectory("cat-integration-")),
				cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}

	/**
	 * Verifies that the program's answer to {@code --version} identifies the harness and a failed one does not.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void checksIdentity() throws IOException
	{
		CONTRACT.checksIdentity();
	}

	/**
	 * Verifies that a home without configuration files is accepted.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void acceptsFreshConfiguration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.acceptsFreshConfiguration(cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}

	/**
	 * Verifies that registration makes the harness load the registered version.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void registersVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.registersVersion(cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}

	/**
	 * Verifies that registration fails when install wrote no plugin.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void refusesMissingPlugin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.refusesMissingPlugin(cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}

	/**
	 * Verifies that unregistration keeps the data when asked to keep it.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void unregistersKeepingDataOnRequest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.unregistersKeepingDataOnRequest(
				cleanup.add(Files.createTempDirectory("cat-integration-")),
				cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}

	/**
	 * Verifies which version folders the harness runs instead of the registered one.
	 */
	@Test
	public void ordersVersionFolders()
	{
		CONTRACT.ordersVersionFolders();
	}

	/**
	 * Verifies that the runner exceptions of the installation guide are removed once.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void removesRunnerExceptionsOnce() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.removesRunnerExceptionsOnce(cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}

	/**
	 * Verifies that the maintained permissions are added once and removed completely.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void installsPermissionsOnce() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.installsPermissionsOnce(cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}

	/**
	 * Verifies that the sandbox entries are added once and removed completely.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void installsSandboxEntriesOnce() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.installsSandboxEntriesOnce(
				cleanup.add(Files.createTempDirectory("cat-integration-")),
				cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}

	/**
	 * Verifies that consent is asked once and its entries are explained and removed.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void asksConsentOnce() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.asksConsentOnce(
				cleanup.add(Files.createTempDirectory("cat-integration-")),
				cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}

	/**
	 * Verifies that a sandbox entry in a shared setting of the user's is explained once.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void describesSharedEntriesOnce() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.describesSharedEntriesOnce(
				cleanup.add(Files.createTempDirectory("cat-integration-")),
				cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}

	/**
	 * Verifies that uninstall tells the user about a consented entry that the user changed.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void leavesChangedEntry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.leavesChangedEntry(
				cleanup.add(Files.createTempDirectory("cat-integration-")),
				cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}

	/**
	 * Verifies that no added entries need no explanation.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void describesNothingAdded() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.describesNothingAdded(cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}

	/**
	 * Verifies that the groups of the recorded entries hold entries and name the files of the recorded entries.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void groupsRecordedEntries() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CONTRACT.groupsRecordedEntries(
				cleanup.add(Files.createTempDirectory("cat-integration-")),
				cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}
}
