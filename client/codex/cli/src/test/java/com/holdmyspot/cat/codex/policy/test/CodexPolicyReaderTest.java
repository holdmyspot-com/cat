package com.holdmyspot.cat.codex.policy.test;

import static com.holdmyspot.cat.codex.policy.test.CodexParentSession.toml;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.policy.CodexPolicyReader;
import com.holdmyspot.cat.codex.policy.CodexSandbox;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.login.KeyringReach;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Verifies that {@link CodexPolicyReader} rebuilds a Codex session's sandbox policy from Codex's command line and
 * configuration files the way Codex 0.160.0 resolves them.
 */
public final class CodexPolicyReaderTest
{
	private static final String LEGACY_WITH_NETWORK = """
		sandbox_mode = "workspace-write"
		[sandbox_workspace_write]
		network_access = true
		""";

	/**
	 * Creates a test instance.
	 */
	public CodexPolicyReaderTest()
	{
	}

	/**
	 * Verifies that the legacy {@code workspace-write} mode lets the commands write the working directory and the
	 * {@code writable_roots}, keeps them from writing the checkout's {@code .git} folder, and that
	 * {@code network_access} lets them reach every host.
	 */
	@Test
	public void legacyWorkspaceWriteRoots() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			Path extra = session.folder("other/extra");
			session.write("home/.codex/config.toml", LEGACY_WITH_NETWORK + "writable_roots = [" + toml(extra) + "]\n");

			ParentPolicy policy = session.read("exec", "--skip-git-repo-check", "summarize");

			requireThat(policy.sandboxed(), "sandboxed").isTrue();
			requireThat(policy.writeRoots(), "writeRoots").contains(session.project()).contains(extra);
			requireThat(policy.writeDenials(), "writeDenials").contains(session.project().resolve(".git"));
			requireThat(policy.hosts(), "hosts").isEqualTo(HostAccess.ALL);
		}
	}

	/**
	 * Verifies that a permissions profile that extends {@code :workspace} adds its {@code write} entries to the
	 * built-in writes, and that its {@code none} entries keep the commands from reading or writing a path.
	 */
	@Test
	public void profileExtendsWorkspace() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			Path secret = session.folder("project/secret");
			Path extra = session.folder("other/extra");
			session.write("home/.codex/config.toml", """
				default_permissions = "p"
				[permissions.p]
				extends = ":workspace"
				[permissions.p.filesystem]
				%s = "none"
				%s = "write"
				[permissions.p.network]
				enabled = true
				""".formatted(toml(secret), toml(extra)));

			ParentPolicy policy = session.read("exec", "summarize");

			requireThat(policy.writeRoots(), "writeRoots").contains(session.project()).contains(extra);
			requireThat(policy.writeDenials(), "writeDenials").contains(secret);
			requireThat(policy.readDenials(), "readDenials").contains(secret);
			requireThat(policy.hosts(), "hosts").isEqualTo(HostAccess.ALL);
		}
	}

	/**
	 * Verifies that a Codex sandbox whose network access is off keeps its commands from the operating system's local
	 * services, and that one with full network access lets them reach them.
	 *
	 * @throws IOException if a path cannot be resolved
	 */
	@Test
	public void noNetworkNoLocalServices() throws IOException
	{
		// Requirement: R-I19
		requireThat(new CodexSandbox(true, List.of(), HostAccess.NONE, false).toParentPolicy().allowLocalServices(),
			"off").isFalse();
		requireThat(new CodexSandbox(true, List.of(), HostAccess.ALL, false).toParentPolicy().allowLocalServices(),
			"full").isTrue();
		requireThat(CodexSandbox.UNSANDBOXED.toParentPolicy().allowLocalServices(), "unsandboxed").isTrue();
	}

	/**
	 * Returns Codex configurations and whether each lets the session's commands reach the operating system's local
	 * services: full network access does, and network access that Codex's managed proxy limits to listed hosts does
	 * not. The reader refuses a session whose network access is off, which {@link #noNetworkNoLocalServices()} covers.
	 *
	 * @return the configuration and the expected access
	 */
	@DataProvider
	public Object[][] localServices()
	{
		return new Object[][]
			{
				{LEGACY_WITH_NETWORK, true},
				{"""
					default_permissions = "p"
					[permissions.p]
					extends = ":workspace"
					[permissions.p.network]
					enabled = true
					""", true},
				{"""
					default_permissions = "p"
					[features]
					network_proxy = true
					[permissions.p]
					extends = ":workspace"
					[permissions.p.network]
					enabled = true
					allow_local_binding = true
					domains = { "api.example.com" = "allow" }
					""", false}
			};
	}

	/**
	 * Verifies that a Codex session's commands may reach the operating system's local services, such as the session
	 * bus, exactly when its network access is full, since Codex's sandbox denies the socket connection under a
	 * restricted network.
	 *
	 * @param configuration the session's Codex configuration
	 * @param reaches       {@code true} if the commands reach the local services
	 */
	@Test(dataProvider = "localServices")
	public void localServicesNeedFullNetwork(String configuration, boolean reaches) throws IOException, PolicyRefusal
	{
		// Requirement: R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			session.write("home/.codex/config.toml", configuration);

			ParentPolicy policy = session.read("exec", "summarize");

			requireThat(policy.sandboxed(), "sandboxed").isTrue();
			requireThat(policy.allowLocalServices(), "allowLocalServices").isEqualTo(reaches);
			Path bus = session.folder("run/user").resolve("bus");
			Optional<String> unreachable = KeyringReach.unreachable(policy, Platform.detect("Linux", "amd64"),
				Map.of(KeyringReach.BUS_ADDRESS_VARIABLE, "unix:path=" + bus));
			requireThat(unreachable.isEmpty(), "reachesKeyring").isEqualTo(reaches);
		}
	}

	/**
	 * Returns whether a proxied profile lets its commands bind local addresses, and whether they then reach a local
	 * host that the profile allows.
	 *
	 * @return the profile's local binding and whether a local host is reachable
	 */
	@DataProvider
	public Object[][] proxiedLocalHosts()
	{
		return new Object[][]
		{
			{true, true},
			{false, false}
		};
	}

	/**
	 * Verifies that Codex's managed network proxy lets the commands reach the profile's allowed public hosts, and its
	 * allowed local hosts only when the profile lets them bind local addresses: otherwise the proxy blocks every local
	 * or private address.
	 *
	 * @param binds      {@code true} if the profile lets the commands bind local addresses
	 * @param reachLocal {@code true} if the commands reach the allowed local hosts
	 */
	@Test(dataProvider = "proxiedLocalHosts")
	public void proxyBlocksLocalHostsWithoutBinding(boolean binds, boolean reachLocal) throws IOException,
		PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			// Loopback, private, IPv6 loopback and IPv6 unique local addresses
			String localHosts = """
				"localhost" = "allow", "127.0.0.1" = "allow", "10.1.2.3" = "allow", "[::1]" = "allow", \
				"fd00::1" = "allow"\
				""";
			session.write("home/.codex/config.toml", """
				default_permissions = "p"
				[features]
				network_proxy = true
				[permissions.p]
				extends = ":workspace"
				[permissions.p.network]
				enabled = true
				allow_local_binding = %s
				domains = { "api.example.com" = "allow", %s }
				""".formatted(binds, localHosts));

			ParentPolicy policy = session.read("exec", "summarize");

			requireThat(policy.hosts().allows("api.example.com"), "public").isTrue();
			for (String entry : localHosts.split(", "))
			{
				String local = entry.substring(1, entry.indexOf('"', 1));
				requireThat(policy.hosts().allows(local), local).isEqualTo(reachLocal);
			}
			requireThat(policy.allowLocalBinding(), "allowLocalBinding").isEqualTo(binds);
		}
	}

	/**
	 * Verifies that {@code -c} overrides whose values are inline tables select and define the permissions profile,
	 * in the form that CAT's runners start Codex with.
	 */
	@Test
	public void inlineTableOverride() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			Path hidden = session.folder("other/hidden");
			Path locks = session.folder("other/locks");

			ParentPolicy policy = session.read("exec", "--skip-git-repo-check", "--dangerously-bypass-hook-trust", "-c",
				"default_permissions=\"parent\"", "-c", "permissions.parent={extends=\":workspace\",filesystem={" +
					toml(hidden) + "=\"none\"," + toml(locks) + "=\"write\"},network={enabled=true}}", "summarize");

			requireThat(policy.readDenials(), "readDenials").contains(hidden);
			requireThat(policy.writeRoots(), "writeRoots").contains(locks).contains(session.project());
			requireThat(policy.hosts(), "hosts").isEqualTo(HostAccess.ALL);
		}
	}

	/**
	 * Verifies that {@code -p} layers the named profile file over the user's configuration.
	 */
	@Test
	public void profileFileSelection() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			Path userRoot = session.folder("other/user");
			Path profileRoot = session.folder("other/profile");
			session.write("home/.codex/config.toml", LEGACY_WITH_NETWORK + "writable_roots = [" + toml(userRoot) +
				"]\n");
			session.write("home/.codex/work.config.toml", "[sandbox_workspace_write]\nwritable_roots = [" +
				toml(profileRoot) + "]\n");

			ParentPolicy policy = session.read("-p", "work");

			requireThat(policy.writeRoots(), "writeRoots").contains(profileRoot).doesNotContain(userRoot);
		}
	}

	/**
	 * Verifies that {@code --add-dir} lets the commands write another folder.
	 */
	@Test
	public void addDirIsWritable() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			Path added = session.folder("other/added");
			session.write("home/.codex/config.toml", LEGACY_WITH_NETWORK);

			ParentPolicy policy = session.read("--add-dir", added.toString());

			requireThat(policy.writeRoots(), "writeRoots").contains(added);
			requireThat(policy.writeDenials(), "writeDenials").contains(added.resolve(".git"));
		}
	}

	/**
	 * Returns command lines that run the session without a sandbox.
	 *
	 * @return the arguments of each command line
	 */
	@DataProvider
	public Object[][] unsandboxedCommandLines()
	{
		return new Object[][]
			{
				{new String[]{"-s", "danger-full-access"}},
				{new String[]{"exec", "--sandbox=danger-full-access", "summarize"}},
				{new String[]{"--dangerously-bypass-approvals-and-sandbox"}},
				{new String[]{"exec", "--yolo", "summarize"}},
				{new String[]{"-c", "default_permissions=\":danger-full-access\""}}
			};
	}

	/**
	 * Verifies that {@code danger-full-access} and the bypass flag make the session unsandboxed.
	 *
	 * @param arguments the arguments of the Codex process
	 */
	@Test(dataProvider = "unsandboxedCommandLines")
	public void dangerFullAccessUnsandboxed(String[] arguments) throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			session.write("home/.codex/config.toml", LEGACY_WITH_NETWORK);

			ParentPolicy policy = session.read(arguments);

			requireThat(policy.sandboxed(), "sandboxed").isFalse();
		}
	}

	/**
	 * Verifies that a session that runs against a remote server is refused.
	 */
	@Test(expectedExceptions = PolicyRefusal.class, expectedExceptionsMessageRegExp = ".*--remote.*")
	public void remoteRefused() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			session.write("home/.codex/config.toml", LEGACY_WITH_NETWORK);

			session.read("--remote", "ws://127.0.0.1:4500");
		}
	}

	/**
	 * Verifies that a hook that Codex's shared background server runs is refused, and that the fix names
	 * {@code --no-daemon}. The server's command line is the one that Codex 0.160.0 starts it with.
	 */
	@Test(expectedExceptions = PolicyRefusal.class, expectedExceptionsMessageRegExp = ".*shared.*--no-daemon.*")
	public void sharedServerRefused() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			session.write("home/.codex/config.toml", LEGACY_WITH_NETWORK);

			session.read("app-server", "--listen", "unix://", "--managed-daemon");
		}
	}

	/**
	 * Verifies that an option that the reader does not know is refused by name.
	 */
	@Test(expectedExceptions = PolicyRefusal.class, expectedExceptionsMessageRegExp = ".*--frobnicate.*")
	public void unknownOptionRefused() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			session.write("home/.codex/config.toml", LEGACY_WITH_NETWORK);

			session.read("exec", "--frobnicate", "summarize");
		}
	}

	/**
	 * Returns the bare and absolute argv[0] forms of the Codex Linux sandbox helper.
	 *
	 * @return the helper's argv[0] values
	 */
	@DataProvider
	public Object[][] sandboxHelperArgumentZeros()
	{
		return new Object[][]
		{
			{"codex-linux-sandbox"},
			{"/usr/libexec/codex-linux-sandbox"}
		};
	}

	/**
	 * Verifies that a Codex sandbox helper does not replace the real Codex process as the source of the session's
	 * command line.
	 *
	 * @param argumentZero the helper's argv[0]
	 */
	@Test(dataProvider = "sandboxHelperArgumentZeros")
	public void selectsOuterCodexAfterSandboxHelper(String argumentZero)
	{
		ProcessDescription helper = new ProcessDescription(102, Optional.empty(), Optional.of("/usr/bin/codex"),
			Optional.of(argumentZero), Optional.of(List.of("--sandbox-helper-option")));
		ProcessDescription codex = new ProcessDescription(103, Optional.empty(), Optional.of("/usr/bin/codex"),
			Optional.of("codex"), Optional.of(List.of("exec", "--sandbox=workspace-write", "summarize")));

		Optional<ProcessDescription> selected = CodexPolicyReader.codexProcess(List.of(helper, codex));

		requireThat(selected, "selected").isEqualTo(Optional.of(codex));
	}

	/**
	 * Verifies that the reader parses the outer Codex command line after skipping helper-only options.
	 *
	 * @throws IOException   if the fixture cannot be written
	 * @throws PolicyRefusal if the command line is refused
	 */
	@Test
	public void parsesOuterArgumentsAfterSandboxHelper() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			session.write("home/.codex/config.toml", LEGACY_WITH_NETWORK);

			ParentPolicy policy = session.readForCommandWithSandboxHelper("exec", "--sandbox=workspace-write",
				"summarize");

			requireThat(policy.sandboxed(), "sandboxed").isTrue();
			requireThat(policy.hosts(), "hosts").isEqualTo(HostAccess.ALL);
		}
	}

	/**
	 * Verifies that an unknown option on the outer Codex command line is still refused after the helper is skipped.
	 *
	 * @throws IOException   if the fixture cannot be written
	 * @throws PolicyRefusal if the outer command line is refused
	 */
	@Test(expectedExceptions = PolicyRefusal.class, expectedExceptionsMessageRegExp = ".*--frobnicate.*")
	public void refusesUnknownOuterOptionAfterHelper() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			session.write("home/.codex/config.toml", LEGACY_WITH_NETWORK);

			session.readForCommandWithSandboxHelper("exec", "--frobnicate", "summarize");
		}
	}

	/**
	 * Verifies that a Codex executable with another argv[0] remains a host candidate.
	 */
	@Test
	public void unfamiliarArgumentZeroDoesNotSkipCodex()
	{
		ProcessDescription codex = new ProcessDescription(103, Optional.empty(), Optional.of("/usr/bin/codex"),
			Optional.of("codex-next-launcher"), Optional.of(List.of("--frobnicate")));

		Optional<ProcessDescription> selected = CodexPolicyReader.codexProcess(List.of(codex));

		requireThat(selected, "selected").isEqualTo(Optional.of(codex));
	}

	/**
	 * Verifies that a helper without an outer Codex process does not count as the host.
	 *
	 * @param argumentZero the helper's argv[0]
	 */
	@Test(dataProvider = "sandboxHelperArgumentZeros")
	public void noHostAfterSandboxHelper(String argumentZero)
	{
		ProcessDescription helper = new ProcessDescription(102, Optional.empty(), Optional.of("/usr/bin/codex"),
			Optional.of(argumentZero), Optional.of(List.of("--sandbox-helper-option")));

		Optional<ProcessDescription> selected = CodexPolicyReader.codexProcess(List.of(helper));

		requireThat(selected, "selected").isEmpty();
	}

	/**
	 * Verifies that the project's {@code .codex/config.toml} applies when the user trusts the project.
	 */
	@Test
	public void trustedProjectLayerApplies() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			Path projectRoot = session.folder("other/project-root");
			trustProject(session, "trusted", projectRoot);

			ParentPolicy policy = session.read("exec", "summarize");

			requireThat(policy.writeRoots(), "writeRoots").contains(projectRoot);
		}
	}

	/**
	 * Verifies that the project's {@code .codex/config.toml} does not apply when the user does not trust the project.
	 */
	@Test
	public void untrustedProjectLayerIgnored() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			Path projectRoot = session.folder("other/project-root");
			trustProject(session, "untrusted", projectRoot);

			ParentPolicy policy = session.read("exec", "summarize");

			requireThat(policy.writeRoots(), "writeRoots").doesNotContain(projectRoot);
		}
	}

	/**
	 * Writes a user configuration that trusts or distrusts the project, and a project configuration that adds a
	 * writable root.
	 *
	 * @param session    the session
	 * @param trustLevel the project's {@code trust_level}
	 * @param root       the root that the project's configuration adds
	 * @throws IOException if a file cannot be written
	 */
	private static void trustProject(CodexParentSession session, String trustLevel, Path root) throws IOException
	{
		session.write("home/.codex/config.toml", LEGACY_WITH_NETWORK + "\n[projects." + toml(session.project()) +
			"]\ntrust_level = \"" + trustLevel + "\"\n");
		session.write("project/.codex/config.toml", "[sandbox_workspace_write]\nnetwork_access = true\n" +
			"writable_roots = [" + toml(root) + "]\n");
	}

	/**
	 * Returns the machine-wide files that Codex reads as requirements or as managed configuration.
	 *
	 * @return the name of each file
	 */
	@DataProvider
	public Object[][] managedFiles()
	{
		return new Object[][]{{"requirements.toml"}, {"managed_config.toml"}, {"config.toml"}};
	}

	/**
	 * Verifies that a machine-wide file that the reader cannot parse is refused.
	 *
	 * @param name the file's name
	 */
	@Test(dataProvider = "managedFiles", expectedExceptions = PolicyRefusal.class,
		expectedExceptionsMessageRegExp = ".*cannot parse.*")
	public void unparsableManagedFileRefused(String name) throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			session.write("home/.codex/config.toml", LEGACY_WITH_NETWORK);
			session.writeSystem(name, "sandbox_mode = [unterminated\n");

			session.read("exec", "summarize");
		}
	}

	/**
	 * Verifies that macOS managed preferences, which the reader cannot parse, are refused.
	 */
	@Test(expectedExceptions = PolicyRefusal.class, expectedExceptionsMessageRegExp = ".*managed preferences.*")
	public void managedPreferencesRefused() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			session.write("home/.codex/config.toml", LEGACY_WITH_NETWORK);
			session.write("managed.plist", "<plist/>");

			session.read("exec", "summarize");
		}
	}

	/**
	 * Verifies that a sandbox whose network is off is refused, and that the fix names both the legacy key and the
	 * permissions profile key that turn it on.
	 */
	@Test(expectedExceptions = PolicyRefusal.class, expectedExceptionsMessageRegExp = ".*network access off.*" +
		"sandbox_workspace_write\\.network_access = true.*\\[permissions\\..*\\.network] enabled = true.*")
	public void networkOffRefused() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			session.write("home/.codex/config.toml", "sandbox_mode = \"workspace-write\"\n");

			session.read("exec", "summarize");
		}
	}

	/**
	 * Verifies that a configuration file below the machine's {@code /tmp}, which the {@code workspace-write} mode lets
	 * the session's commands write, is refused.
	 */
	@Test(expectedExceptions = PolicyRefusal.class, expectedExceptionsMessageRegExp = ".*lets its commands write.*" +
		"config\\.toml.*")
	public void configurationBelowSlashTmpRefused() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexParentSession session = new CodexParentSession(cleanup.add(Files.createTempDirectory("codex-policy")));
			session.write("home/.codex/config.toml", LEGACY_WITH_NETWORK);
			// The folder that holds the session's files, as the machine's temporary folder holds a test's files
			session.slashTmp(session.project().getParent());

			session.read("exec", "summarize");
		}
	}
}
