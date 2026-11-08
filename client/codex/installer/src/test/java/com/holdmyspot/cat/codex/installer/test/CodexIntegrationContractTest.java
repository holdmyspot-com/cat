package com.holdmyspot.cat.codex.installer.test;

import com.holdmyspot.cat.codex.installer.CodexActivation;
import com.holdmyspot.cat.codex.installer.CodexIntegration;
import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.contract.test.HarnessIntegrationContract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the {@link HarnessIntegrationContract} against {@link CodexIntegration}, with Codex's program as
 * {@link CodexProgram} plays it.
 */
public final class CodexIntegrationContractTest
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
				return new CodexIntegration();
			}

			@Override
			public PluginActivation activation()
			{
				return new CodexActivation();
			}

			@Override
			public HarnessCli cli(Path home)
			{
				return CodexProgram.cli(home);
			}

			@Override
			public void writePlugin(Path home, PluginVersion version) throws IOException
			{
				CodexProgram.writeMarketplaceSource(home, version);
			}

			@Override
			public CommandResult versionAnswer()
			{
				return CodexProgram.versionAnswer();
			}

			@Override
			public boolean deletesData()
			{
				return false;
			}

			@Override
			public boolean selectsByName()
			{
				return true;
			}

			@Override
			public void arrangeRunnerExceptions(HarnessIntegration integration, Path home) throws IOException
			{
				Path launcher = new CodexInstallation(home).versionFolder(PluginVersion.parse("1.0+b0123456789ab")).
					resolve("bin/codex-runner");
				Path rules = Files.createDirectories(home.resolve("rules")).resolve("cat.rules");
				Files.writeString(rules, "prefix_rule(\n\tpattern = [" + MAPPER.writeValueAsString(launcher.toString()) +
					"],\n\tdecision = \"allow\",\n)\n");
			}

			@Override
			public boolean arrangeConsent(HarnessIntegration integration, Path home) throws IOException
			{
				// A built-in profile is shared by every project that selects it
				Files.writeString(home.resolve("config.toml"), "default_permissions = \":workspace\"\n");
				return true;
			}

			@Override
			public void changeConsentedEntry(HarnessIntegration integration, Path home) throws IOException
			{
				Path configuration = home.resolve("config.toml");
				Files.writeString(configuration, Files.readString(configuration).
					replace("default_permissions = \"cat-workspace\"", "default_permissions = \":read-only\""));
			}

			@Override
			public boolean arrangeSharedSetting(HarnessIntegration integration, Path home) throws IOException
			{
				Files.writeString(home.resolve("config.toml"), "default_permissions = \"mine\"\n\n" +
					"[permissions.mine]\nextends = \":workspace\"\n");
				return true;
			}

			@Override
			public Map<String, Set<EntryEffect>> effects()
			{
				// The rules file lets the installer's operations run without asking and outside the sandbox
				return Map.of(
					"rules/cat-installer.rules#",
					Set.of(EntryEffect.RUNS_WITHOUT_ASKING, EntryEffect.RUNS_OUTSIDE_SANDBOX),
					"config.toml#sandbox_workspace_write.writable_roots", Set.of(EntryEffect.SANDBOX_MAY_WRITE));
			}
		});

	/**
	 * Creates the test.
	 */
	public CodexIntegrationContractTest()
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
}
