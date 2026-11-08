package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.policy.CodexPolicyReader;
import com.holdmyspot.cat.codex.policy.CodexSystemFiles;
import com.holdmyspot.cat.codex.runner.CodexSessionLauncherCli;
import com.holdmyspot.cat.codex.runner.TomlStrings;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.contract.test.ParentPolicySourceContract;
import com.holdmyspot.cat.common.runner.launcher.LauncherRecord;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Tests the sandbox policy that the Codex session launcher serves its session with: the policy that the command line
 * of the Codex process that the hook named and the session's configuration give, narrowed by the policy that Codex
 * records for the subagent thread that asks.
 * <p>
 * The rollout records follow those of Codex 0.160.0 ({@code codex exec}, Linux), with the instructions left out. The
 * {@link ParentPolicySource} contract runs against the same source, over the session's configuration and a stand-in
 * for the Codex process.
 */
public final class CodexLauncherPolicyTest extends ParentPolicySourceContract
{
	private static final long CODEX_PID = 5151;
	private static final Instant CODEX_START = Instant.parse("2026-11-05T12:00:00.456Z");
	/**
	 * The session that the hook described, which is also the identifier of its main thread.
	 */
	private static final String SESSION_ID = "01a1a8eb-3d83-7d73-b243-c636c2041ee1";
	/**
	 * A subagent thread.
	 */
	private static final String SUBAGENT_THREAD = "01a1a8f0-1111-7222-8333-444455556666";

	/**
	 * Creates the test.
	 */
	public CodexLauncherPolicyTest()
	{
	}

	@Override
	protected String harnessName()
	{
		return "Codex";
	}

	@Override
	protected String quoted(String value)
	{
		return "\"" + TomlStrings.escape(value) + "\"";
	}

	@Override
	protected boolean followsWidenedSettings()
	{
		return true;
	}

	@Override
	protected Session session() throws IOException
	{
		return new CodexContractSession();
	}

	/**
	 * Verifies that the launcher reads the policy of the session that the hook described, from the command line of
	 * the Codex process that the hook named.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void readsPolicyOfRecordedSession() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory("codex-launcher-policy")));
			ParentPolicySource source = fixture.source(Optional.of(CODEX_START));

			ParentPolicy policy = source.read(Optional.empty());

			requireThat(source.harnessName(), "harnessName").isEqualTo("Codex");
			requireThat(policy.sandboxed(), "sandboxed").isTrue();
			requireThat(policy.writeRoots(), "writeRoots").contains(fixture.project);
			requireThat(policy.hosts(), "hosts").isEqualTo(HostAccess.ALL);
		}
	}

	/**
	 * Verifies that the launcher refuses to read a policy once the Codex process that the hook named has ended, even
	 * when another process has taken its identifier.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesReplacedProcess() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory("codex-launcher-policy")));
			for (Optional<Instant> start : List.of(Optional.of(CODEX_START.plusSeconds(60)), Optional.<Instant>empty()))
			{
				ParentPolicySource source = fixture.source(start);
				try
				{
					ParentPolicy policy = source.read(Optional.empty());
					throw new AssertionError("The reading was not refused: " + policy);
				}
				catch (PolicyRefusal e)
				{
					requireThat(e.getMessage(), "refusal").contains(String.valueOf(CODEX_PID));
				}
			}
		}
	}

	/**
	 * Verifies that a launch that a subagent thread asks for, whose latest turn Codex records as read-only, gets a
	 * policy without the writes that the session's main thread may make.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void readOnlySubagentNarrowsPolicy() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory("codex-launcher-policy")));
			fixture.readOnlyRollout(SUBAGENT_THREAD, SESSION_ID);
			ParentPolicySource source = fixture.source(Optional.of(CODEX_START));

			ParentPolicy policy = source.read(Optional.of(SUBAGENT_THREAD));

			requireThat(policy.sandboxed(), "sandboxed").isTrue();
			requireThat(policy.writeRoots(), "writeRoots").doesNotContain(fixture.project);
		}
	}

	/**
	 * Verifies that a launch that names a thread of another session gets the policy of the session that the hook
	 * described, even when that thread's latest turn is read-only.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void threadOfOtherSessionKeepsSessionPolicy() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory("codex-launcher-policy")));
			fixture.readOnlyRollout(SUBAGENT_THREAD, "01a1a8f0-9999-7999-8999-999999999999");
			ParentPolicySource source = fixture.source(Optional.of(CODEX_START));

			ParentPolicy policy = source.read(Optional.of(SUBAGENT_THREAD));

			requireThat(policy.writeRoots(), "writeRoots").contains(fixture.project);
		}
	}

	/**
	 * Verifies that a launch that names a thread in a form that Codex never gives a thread gets the session's policy,
	 * even when a read-only rollout of the session carries that name.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void malformedThreadIsIgnored() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory("codex-launcher-policy")));
			String malformed = "thread-a";
			fixture.readOnlyRollout(malformed, SESSION_ID);
			ParentPolicySource source = fixture.source(Optional.of(CODEX_START));

			ParentPolicy policy = source.read(Optional.of(malformed));

			requireThat(policy.writeRoots(), "writeRoots").contains(fixture.project);
		}
	}

	/**
	 * Supplies the writes and hosts that a child's sandbox lacks and the instruction that names the Codex settings
	 * which allow them.
	 *
	 * @return the missing writes, the missing hosts and the instruction
	 */
	@DataProvider
	public Object[][] grantInstructions()
	{
		String where = " in Codex's config.toml, where <profile> is the permissions profile that default_permissions " +
			"names";
		String legacy = "; a session that sets sandbox_mode instead lists the paths in " +
			"sandbox_workspace_write.writable_roots";
		Path m2 = Path.of("/data/m2");
		Path gradle = Path.of("/data/gradle");
		String quotedM2 = quoted(m2);
		String quotedGradle = quoted(gradle);
		return new Object[][]
			{
				{List.of(m2, gradle), Set.of("a.example.com", "b.example.com"),
					"add " + quotedM2 + " = \"write\" and " + quotedGradle + " = \"write\" under " +
						"[permissions.<profile>.filesystem] and \"a.example.com\" = \"allow\" and " +
						"\"b.example.com\" = \"allow\" under [permissions.<profile>.network.domains]" + where + legacy},
				{List.of(m2), Set.of(),
					"add " + quotedM2 + " = \"write\" under [permissions.<profile>.filesystem]" + where + legacy},
				{List.of(), Set.of("api.example.com"),
					"add \"api.example.com\" = \"allow\" under [permissions.<profile>.network.domains]" + where},
				{List.of(), Set.of("localhost"),
					"add \"localhost\" = \"allow\" under [permissions.<profile>.network.domains] and " +
						"allow_local_binding = true under [permissions.<profile>.network]" + where},
				{List.of(), Set.of("api.example.com", "localhost"),
					"add \"api.example.com\" = \"allow\" and \"localhost\" = \"allow\" under " +
						"[permissions.<profile>.network.domains] and allow_local_binding = true under " +
						"[permissions.<profile>.network]" + where}
			};
	}

	/**
	 * Verifies that the launcher tells the user which Codex settings to add a missing write and host to.
	 *
	 * @param writes   the paths that the session's sandbox lacks
	 * @param hosts    the hosts that the session's sandbox lacks
	 * @param expected the instruction
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "grantInstructions")
	public void namesSettingsThatAllowMissingAccess(List<Path> writes, Set<String> hosts, String expected)
		throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory("codex-launcher-policy")));
			ParentPolicySource source = fixture.source(Optional.of(CODEX_START));

			requireThat(source.permissionEntries(writes, hosts), "entries").isEqualTo(expected);
		}
	}

	/**
	 * Returns a path as the TOML basic string that names it in the instruction, for a path whose only characters that
	 * TOML escapes are the separators of a Windows path.
	 *
	 * @param path a path
	 * @return the string, with its quotes
	 */
	private static String quoted(Path path)
	{
		return "\"" + path.toString().replace("\\", "\\\\") + "\"";
	}

	/**
	 * Supplies paths whose text is not a valid TOML basic string until it is escaped. A path may contain only the
	 * characters that its platform's file system accepts: Windows rejects {@code "} and control characters in a path
	 * and treats a backslash as a separator, whereas a POSIX path may contain all of them.
	 *
	 * @return the paths
	 */
	@DataProvider
	public Object[][] pathsThatTomlEscapes()
	{
		if (Platform.current().isWindows())
		{
			return new Object[][]
				{
					{Path.of("C:\\Users\\gili\\.m2\\repository")},
					{Path.of("C:\\data\\say 'hi'\\back\\slash")},
					{Path.of("C:\\data\\a#b=c[d]\\\177")}
				};
		}
		return new Object[][]
			{
				{Path.of("C:\\Users\\gili\\.m2\\repository")},
				{Path.of("/data/say \"hi\"/back\\slash")},
				{Path.of("/data/two\nlines\tand\ttabs\001\177")}
			};
	}

	/**
	 * Verifies that the instruction names a missing write as a TOML string that Codex reads back as the path, so a
	 * user who pastes it into config.toml gets a configuration that Codex accepts.
	 *
	 * @param write a path that the session's sandbox lacks
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "pathsThatTomlEscapes")
	public void namesMissingWriteAsTomlString(Path write) throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory("codex-launcher-policy")));
			ParentPolicySource source = fixture.source(Optional.of(CODEX_START));

			String instruction = source.permissionEntries(List.of(write), Set.of());

			JsonNode filesystem = TomlMapper.builder().build().
				readTree(entriesUnder("permissions.<profile>.filesystem", instruction));
			requireThat(filesystem.propertyNames(), "paths").containsExactly(Set.of(write.toString()));
			requireThat(filesystem.path(write.toString()).asString(), "access").isEqualTo("write");
		}
	}

	/**
	 * Verifies that the instruction names a missing host as a TOML string that Codex reads back as the host.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void namesMissingHostAsTomlString() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup.add(Files.createTempDirectory("codex-launcher-policy")));
			ParentPolicySource source = fixture.source(Optional.of(CODEX_START));
			String host = "odd\"host\\name";

			String instruction = source.permissionEntries(List.of(), Set.of(host));

			JsonNode domains = TomlMapper.builder().build().
				readTree(entriesUnder("permissions.<profile>.network.domains", instruction));
			requireThat(domains.propertyNames(), "hosts").containsExactly(Set.of(host));
			requireThat(domains.path(host).asString(), "access").isEqualTo("allow");
		}
	}

	/**
	 * Returns the entries that an instruction tells the user to add under one table.
	 *
	 * @param table       the name of the table
	 * @param instruction the instruction
	 * @return the entries, as the lines of a TOML document
	 */
	private static String entriesUnder(String table, String instruction)
	{
		String suffix = " under [" + table + "]";
		int end = instruction.indexOf(suffix);
		requireThat(end, "end").isNotNegative();
		int start = instruction.lastIndexOf("add ", end) + "add ".length();
		return instruction.substring(start, end).replace(" and ", System.lineSeparator());
	}

	/**
	 * The files of a Codex session whose configuration lets its commands write the working directory and reach every
	 * host, and the folder that stands for the machine's {@code /tmp}.
	 */
	private static final class Fixture
	{
		private final Path home;
		private final Path project;
		private final Path system;
		private final Path slashTmp;

		/**
		 * Creates the files.
		 *
		 * @param folder an empty folder
		 * @throws IOException if the files cannot be created
		 */
		Fixture(Path folder) throws IOException
		{
			Path root = folder.toRealPath();
			home = Files.createDirectories(root.resolve("home"));
			Files.createDirectories(home.resolve(".codex"));
			Files.writeString(home.resolve(".codex/config.toml"), """
				sandbox_mode = "workspace-write"
				[sandbox_workspace_write]
				network_access = true
				""");
			project = Files.createDirectories(root.resolve("project"));
			Files.createDirectories(project.resolve(".git"));
			system = Files.createDirectories(root.resolve("etc-codex"));
			slashTmp = Files.createDirectories(root.resolve("slash-tmp"));
		}

		/**
		 * Rewrites the session's configuration so that it lets the commands write some folders besides the working
		 * directory.
		 *
		 * @param roots the folders
		 * @throws IOException if the file cannot be written
		 */
		void writableRoots(List<Path> roots) throws IOException
		{
			List<String> quoted = new ArrayList<>();
			for (Path root : roots)
				quoted.add("\"" + TomlStrings.escape(root.toString()) + "\"");
			Files.writeString(home.resolve(".codex/config.toml"), """
				sandbox_mode = "workspace-write"
				[sandbox_workspace_write]
				network_access = true
				writable_roots = [%s]
				""".formatted(String.join(", ", quoted)));
		}

		/**
		 * Replaces the session's configuration file with a folder, which cannot be read as a file.
		 *
		 * @throws IOException if the file cannot be replaced
		 */
		void unreadableConfiguration() throws IOException
		{
			Path configuration = home.resolve(".codex/config.toml");
			Files.delete(configuration);
			Files.createDirectory(configuration);
		}

		/**
		 * Rewrites the session's configuration file with text that is not valid TOML.
		 *
		 * @throws IOException if the file cannot be written
		 */
		void unparsableConfiguration() throws IOException
		{
			Files.writeString(home.resolve(".codex/config.toml"), "sandbox_mode = \n");
		}

		/**
		 * Writes, in Codex's home, the rollout of a thread whose latest turn may read every file and write none.
		 *
		 * @param threadId  the thread
		 * @param sessionId the session that the rollout's {@code session_meta} names
		 * @throws IOException if the file cannot be written
		 */
		void readOnlyRollout(String threadId, String sessionId) throws IOException
		{
			String project = JsonMapper.builder().build().writeValueAsString(this.project.toString());
			String records = """
				{"timestamp":"2026-11-05T12:00:00.000Z","type":"session_meta","payload":{"session_id":"%s","id":"%s",\
				"cwd":%s,"originator":"codex_exec","cli_version":"0.160.0","source":"exec"}}
				{"timestamp":"2026-11-05T12:00:01.000Z","type":"turn_context","payload":{"turn_id":"t1","cwd":%s,\
				"workspace_roots":[%s],"approval_policy":"never","sandbox_policy":{"type":"read-only",\
				"network_access":true},"permission_profile":{"type":"managed","file_system":{"type":"restricted",\
				"entries":[{"path":{"type":"special","value":{"kind":"root"}},"access":"read"}]},\
				"network":"enabled"},"model":"gpt-6.1-sol"}}
				""".formatted(sessionId, threadId, project, project, project);
			Path folder = Files.createDirectories(home.resolve(".codex/sessions/2026/11/05"));
			Files.writeString(folder.resolve("rollout-2026-11-05T12-00-00-" + threadId + ".jsonl"), records);
		}

		/**
		 * Returns the launcher's policy source.
		 *
		 * @param reportedStart the start of the process with the hook's process identifier, or empty if none runs
		 * @return the source
		 */
		ParentPolicySource source(Optional<Instant> reportedStart)
		{
			return source(() -> reportedStart);
		}

		/**
		 * Returns the launcher's policy source.
		 *
		 * @param reportedStart returns the start of the process with the hook's process identifier at each reading, or
		 *                      empty if none runs
		 * @return the source
		 */
		ParentPolicySource source(Supplier<Optional<Instant>> reportedStart)
		{
			HarnessProcesses processes = pid ->
			{
				Optional<Instant> start = reportedStart.get();
				if (pid != CODEX_PID || start.isEmpty())
					return Optional.empty();
				return Optional.of(new ProcessDescription(pid, start, Optional.of("/usr/lib/codex/bin/codex"),
					Optional.of(List.of())));
			};
			LauncherRecord record = new LauncherRecord("codex", new ProcessIdentity(CODEX_PID, CODEX_START),
				SESSION_ID, project, Optional.empty(), "startup", home.resolve(".codex"),
				PluginInfo.CAT_VERSION);
			CodexPolicyReader reader = new CodexPolicyReader(new CodexSystemFiles(system,
				List.of(system.resolve("managed.plist")), slashTmp));
			return CodexSessionLauncherCli.policySource(reader, processes, record, Map.of("HOME", home.toString()),
				home);
		}
	}

	/**
	 * A Codex session for the {@link ParentPolicySource} contract, whose Codex process runs until the contract ends
	 * it.
	 */
	private static final class CodexContractSession implements Session
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final Fixture fixture;
		private final Path folder;
		private final ParentPolicySource source;
		private volatile boolean running = true;

		/**
		 * Creates the session.
		 *
		 * @throws IOException if the session's files cannot be created
		 */
		CodexContractSession() throws IOException
		{
			Path root = cleanup.add(Files.createTempDirectory("codex-launcher-policy")).toRealPath();
			this.fixture = new Fixture(root);
			this.folder = Files.createDirectory(root.resolve("outside"));
			this.source = fixture.source(() ->
			{
				if (running)
					return Optional.of(CODEX_START);
				return Optional.empty();
			});
		}

		@Override
		public ParentPolicySource source()
		{
			return source;
		}

		@Override
		public Path folder()
		{
			return folder;
		}

		@Override
		public void allowWrites(List<Path> writes) throws IOException
		{
			fixture.writableRoots(writes);
		}

		@Override
		public Optional<String> readOnlyThread() throws IOException
		{
			fixture.readOnlyRollout(SUBAGENT_THREAD, SESSION_ID);
			return Optional.of(SUBAGENT_THREAD);
		}

		@Override
		public void unreadableSettings() throws IOException
		{
			fixture.unreadableConfiguration();
		}

		@Override
		public void unparsableSettings() throws IOException
		{
			fixture.unparsableConfiguration();
		}

		@Override
		public void end()
		{
			running = false;
		}

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}
}
