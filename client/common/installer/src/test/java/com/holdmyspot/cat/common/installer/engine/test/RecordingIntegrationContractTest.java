package com.holdmyspot.cat.common.installer.engine.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.contract.test.HarnessIntegrationContract;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Runs the {@link HarnessIntegrationContract} against {@link RecordingIntegration}, so that the generic harness of
 * the install tests changes a harness home as the Claude Code and Codex integrations do: once as a harness that keeps
 * the data and runs the registered version, and for the cases that differ, once as a harness that deletes the data
 * unless asked to keep it and runs the version folder whose name sorts last.
 */
public final class RecordingIntegrationContractTest
{
	/**
	 * The key of the setting that install changes with the user's consent.
	 */
	private static final String SELECTION = "settings.json#selection";
	/**
	 * The key of the sandbox entries.
	 */
	private static final String SANDBOX = "settings.json#sandbox";
	/**
	 * The contract over a harness that keeps the data and runs the registered version.
	 */
	private static final HarnessIntegrationContract CONTRACT = new HarnessIntegrationContract(new Subject(false));
	/**
	 * The contract over a harness that deletes the data unless asked to keep it and runs the version folder whose
	 * name sorts last.
	 */
	private static final HarnessIntegrationContract DELETING = new HarnessIntegrationContract(new Subject(true));

	/**
	 * Creates the test.
	 */
	public RecordingIntegrationContractTest()
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
	 * Verifies that the groups of the recorded entries give each configuration setting its effects, hold entries and
	 * items that come from the recorded entries, and name the files of the recorded entries.
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

	/**
	 * Verifies that unregistration keeps the data when asked to keep it, on a harness that deletes it
	 * otherwise.
	 *
	 * @throws IOException if a home cannot be created, written or read
	 */
	@Test
	public void unregistersDeletingDataOnRequest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DELETING.unregistersKeepingDataOnRequest(
				cleanup.add(Files.createTempDirectory("cat-integration-")),
				cleanup.add(Files.createTempDirectory("cat-integration-")));
		}
	}

	/**
	 * Verifies which version folders the harness runs instead of the registered one, on a harness that
	 * runs the folder whose name sorts last.
	 */
	@Test
	public void ordersVersionFoldersByName()
	{
		DELETING.ordersVersionFolders();
	}

	/**
	 * The recording integration and how the generic harness behaves.
	 *
	 * @param deleting whether the harness deletes the data unless asked to keep it and runs the version folder whose
	 *                 name sorts last
	 */
	private record Subject(boolean deleting) implements HarnessIntegrationContract.Subject
	{
		@Override
		public HarnessIntegration integration()
		{
			RecordingIntegration integration = new RecordingIntegration().
				maintain(Map.of("settings.json#allow", List.of("entry"))).
				sandboxing(Map.of(SANDBOX, List.of("locks")));
			if (deleting)
				integration.deletingData().runningGreatestName();
			return integration;
		}

		@Override
		public PluginActivation activation()
		{
			return new RegisteredProvider();
		}

		@Override
		public HarnessCli cli(Path home)
		{
			return RecordingIntegration.cli();
		}

		@Override
		public void writePlugin(Path home, PluginVersion version) throws IOException
		{
			Path stated = new RecordingIntegration().marketplaceFolder(home, version).resolve(PluginDirectory.VERSION);
			Files.createDirectories(stated.getParent());
			Files.writeString(stated, version + "\n");
		}

		@Override
		public CommandResult versionAnswer()
		{
			return new CommandResult(0, "harness-a 1.0\n");
		}

		@Override
		public boolean deletesData()
		{
			return deleting;
		}

		@Override
		public boolean selectsByName()
		{
			return deleting;
		}

		@Override
		public void arrangeRunnerExceptions(HarnessIntegration integration, Path home)
		{
			((RecordingIntegration) integration).removingRunnerExceptions(List.of("CAT removed the runner exception."));
		}

		@Override
		public boolean arrangeConsent(HarnessIntegration integration, Path home)
		{
			((RecordingIntegration) integration).asking("May CAT change the shared setting?",
				Map.of(SELECTION, List.of("built-in")), "CAT changed the shared setting.");
			return true;
		}

		@Override
		public void changeConsentedEntry(HarnessIntegration integration, Path home)
		{
			((RecordingIntegration) integration).retaining(SELECTION, "CAT left the shared setting as you changed it.");
		}

		@Override
		public boolean arrangeSharedSetting(HarnessIntegration integration, Path home)
		{
			((RecordingIntegration) integration).describing(SANDBOX, "CAT added the lock folder to a shared profile.");
			return true;
		}

		@Override
		public Map<String, Set<EntryEffect>> effects()
		{
			return Map.of("settings.json#allow", Set.of(EntryEffect.SETS_VALUE),
				SANDBOX, Set.of(EntryEffect.SETS_VALUE));
		}
	}
}
