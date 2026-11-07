package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.policy.CodexPolicyReader;
import com.holdmyspot.cat.codex.policy.CodexSystemFiles;
import com.holdmyspot.cat.codex.runner.CodexSessionLauncherCli;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * The rollout records follow those of Codex 0.160.0 ({@code codex exec}, Linux), with the instructions left out.
 */
public final class CodexLauncherPolicyTest
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
	 * Supplies paths whose text is not a valid TOML basic string until it is escaped.
	 *
	 * @return the paths
	 */
	@DataProvider
	public Object[][] pathsThatTomlEscapes()
	{
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
			HarnessProcesses processes = pid ->
			{
				if (pid != CODEX_PID || reportedStart.isEmpty())
					return Optional.empty();
				return Optional.of(new ProcessDescription(pid, reportedStart, Optional.of("/usr/lib/codex/bin/codex"),
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
}
