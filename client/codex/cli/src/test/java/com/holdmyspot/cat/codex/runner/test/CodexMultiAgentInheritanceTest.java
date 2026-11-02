package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.runner.CodexConnectionConfiguration;
import com.holdmyspot.cat.codex.runner.CodexParentMultiAgentState;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Tests that a nested Codex session gets the multi-agent settings of the Codex session that starts it, through
 * {@link CodexConnectionConfiguration} and {@link CodexParentMultiAgentState}.
 */
public final class CodexMultiAgentInheritanceTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final TomlMapper TOML = TomlMapper.builder().build();
	private static final String THREAD = "01a1aaaa-bbbb-7ccc-8ddd-eeeeeeeeeeee";
	/**
	 * The text that Codex gives the model in the proactive mode.
	 */
	private static final String PROACTIVE_TEXT = "Proactive multi-agent delegation is active. Any earlier developer " +
		"instruction requiring an explicit user request before spawning sub-agents no longer applies. This mode " +
		"remains active until a later multi-agent mode developer message changes it. User requests override this " +
		"hint.\n\nIf at any point you can parallelize work by delegating tasks to another agent (no matter if you are " +
		"root or subagent), you should do so using collaboration tools if it could save time or improve quality.";

	/**
	 * Returns parent configurations in each form that Codex accepts, with the multi-agent entries that a session must
	 * get.
	 *
	 * @return the parent configuration, and the session's expected {@code [features]} and {@code [agents]} entries in
	 *   TOML
	 */
	@DataProvider
	public Object[][] parentConfigurations()
	{
		return new Object[][]
		{
			{"", "", ""},
			{"""
				[features]
				multi_agent = false
				apps = true
				""", "multi_agent = false", ""},
			{"""
				[features]
				multi_agent_v2 = true
				""", "multi_agent_v2 = true", ""},
			{"""
				[features.multi_agent_v2]
				enabled = true
				multi_agent_mode_hint_text = "Delegate \\"often\\".\\nKeep going."
				usage_hint_text = 'Use agents.'
				""", """
				multi_agent_v2.enabled = true
				multi_agent_v2.multi_agent_mode_hint_text = "Delegate \\"often\\".\\nKeep going."
				multi_agent_v2.usage_hint_text = 'Use agents.'
				""", ""},
			{"""
				features.multi_agent = true
				features.multi_agent_v2 = { enabled = false }
				""", """
				multi_agent = true
				multi_agent_v2.enabled = false
				""", ""},
			{"""
				[agents]
				max_threads = 3
				max_depth = 2
				job_max_runtime_seconds = 600
				interrupt_message = true
				default_subagent_model = "gpt-6-luna"
				""", "", """
				max_threads = 3
				max_depth = 2
				job_max_runtime_seconds = 600
				interrupt_message = true
				"""},
			{"""
				[agents]
				enabled = false
				max_concurrent_threads_per_session = 4
				""", "", """
				enabled = false
				max_concurrent_threads_per_session = 4
				"""}
		};
	}

	/**
	 * Verifies that a session outside a Codex thread gets the parent configuration's multi-agent entries, whichever form
	 * they take, and none of the tables' other entries.
	 *
	 * @param parent   the parent configuration
	 * @param features the session's expected {@code [features]} entries
	 * @param agents   the session's expected {@code [agents]} entries
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "parentConfigurations")
	public void copiesConfiguredEntries(String parent, String features, String agents) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-multi-agent-"));

			JsonNode session = session(root, parent, CodexParentMultiAgentState.NONE, Platform.LINUX_X64);

			requireTable(session, "features", features);
			requireTable(session, "agents", agents);
		}
	}

	/**
	 * Verifies that a Windows session gets the multi-agent entries in the same {@code [features]} table as its sandbox
	 * preference.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void windowsMergesFeatureTables() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-multi-agent-"));

			JsonNode session = session(root, "[features]\nmulti_agent_v2 = true\n", CodexParentMultiAgentState.NONE,
				Platform.WINDOWS_X64);

			requireTable(session, "features", "prefer_mxc = true\nmulti_agent_v2 = true");
		}
	}

	/**
	 * Verifies that a session gets the parent's agent roles that it can use, and not a role whose configuration file
	 * lies outside the session's home and project.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void copiesOnlyAvailableRoles() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-multi-agent-"));
			String outside = root.resolve("host/agents/reviewer.toml").toString().replace("\\", "\\\\");
			String inside = root.resolve("project/agents/tester.toml").toString().replace("\\", "\\\\");

			JsonNode session = session(root, """
				[agents]
				max_threads = 2
				[agents.explorer]
				description = "Reads the code."
				nickname_candidates = ["Scout"]
				[agents.reviewer]
				description = "Reviews."
				config_file = "%s"
				[agents.tester]
				description = "Tests."
				config_file = "%s"
				""".formatted(outside, inside), CodexParentMultiAgentState.NONE, Platform.LINUX_X64);

			JsonNode agents = session.path("agents");
			requireThat(agents.path("max_threads").asInt(), "maxThreads").isEqualTo(2);
			requireTable(agents, "explorer", "description = \"Reads the code.\"\nnickname_candidates = [\"Scout\"]");
			requireThat(agents.has("reviewer"), "reviewerCopied").isFalse();
			requireThat(agents.path("tester").path("config_file").asString(), "testerFile").
				isEqualTo(root.resolve("project/agents/tester.toml").toString());
		}
	}

	/**
	 * Returns parent configurations and rollouts of the parent thread, with the multi-agent entries that a session must
	 * get.
	 *
	 * @return the parent configuration, the rollout's lines, and the session's expected {@code [features]} and
	 *   {@code [agents]} entries in TOML
	 */
	@DataProvider
	public Object[][] parentThreads()
	{
		String proactive = TOML.writeValueAsString(TOML.createObjectNode().put("text", PROACTIVE_TEXT)).
			substring("text = ".length()).strip();
		return new Object[][]
		{
			// A thread that runs the second version in the proactive mode, which its ultra effort selected
			{"", List.of(turnContext("v2"), worldState(true, "\"proactive\"")), """
				multi_agent_v2.enabled = true
				multi_agent_v2.multi_agent_mode_hint_text = %s
				""".formatted(proactive), ""},
			// A thread whose model's catalog entry selected the second version, under a configured custom text
			{"", List.of(turnContext("v2"), worldState(true, "{\"custom\":\"Delegate freely.\"}")), """
				multi_agent_v2.enabled = true
				multi_agent_v2.multi_agent_mode_hint_text = "Delegate freely."
				""", ""},
			// A configured text that the thread does not run with, such as one added after it started
			{"""
				[features.multi_agent_v2]
				enabled = true
				multi_agent_mode_hint_text = "Added later."
				""", List.of(turnContext("v2"), worldState(true, "\"explicitRequestOnly\"")),
				"multi_agent_v2.enabled = true", ""},
			// A patch that changes the mode, and a later one that changes only another field of the section
			{"", List.of(turnContext("v2"), worldState(true, "\"explicitRequestOnly\""),
				"{\"type\":\"world_state\",\"payload\":{\"full\":false,\"state\":{\"multi_agent_mode\":" +
					"{\"mode\":\"proactive\"}}}}",
				"{\"type\":\"world_state\",\"payload\":{\"full\":false,\"state\":{\"multi_agent_mode\":" +
					"{\"usage_hint_hash\":\"b\"}}}}"), """
				multi_agent_v2.enabled = true
				multi_agent_v2.multi_agent_mode_hint_text = %s
				""".formatted(proactive), ""},
			// An earlier Codex release, which recorded the mode in the turn context
			{"", List.of("{\"type\":\"turn_context\",\"payload\":{\"multi_agent_version\":\"v2\"," +
				"\"multi_agent_mode\":{\"custom\":\"Old text.\"}}}"), """
				multi_agent_v2.enabled = true
				multi_agent_v2.multi_agent_mode_hint_text = "Old text."
				""", ""},
			// A thread that runs the first version although its configuration names the second
			{"[features]\nmulti_agent_v2 = true\n", List.of(turnContext("v1")), """
				multi_agent_v2 = false
				multi_agent = true
				""", ""},
			// A thread without multi-agent tools, whose configuration enables them
			{"""
				[features.multi_agent_v2]
				enabled = true
				[agents]
				max_threads = 2
				""", List.of(turnContext("disabled")), """
				multi_agent_v2.enabled = false
				multi_agent = false
				""", """
				max_threads = 2
				enabled = false
				"""}
		};
	}

	/**
	 * Verifies that a session started from a Codex thread runs the multi-agent version and mode that the thread's
	 * latest records show, over a parent configuration entry that contradicts them.
	 *
	 * @param parent   the parent configuration
	 * @param rollout  the lines of the parent thread's rollout
	 * @param features the session's expected {@code [features]} entries
	 * @param agents   the session's expected {@code [agents]} entries
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "parentThreads")
	public void appliesParentThreadState(String parent, List<String> rollout, String features, String agents)
		throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-multi-agent-"));
			Path codexHome = root.resolve("host");
			writeRollout(codexHome, String.join("\n", rollout) + "\n");

			JsonNode session = session(root, parent, CodexParentMultiAgentState.read(JSON, codexHome, THREAD),
				Platform.LINUX_X64);

			requireTable(session, "features", features);
			requireTable(session, "agents", agents);
		}
	}

	/**
	 * Returns parent threads whose recorded state is unavailable.
	 *
	 * @return the thread identifier, and the rollout's content or {@code null} if the thread has none
	 */
	@DataProvider
	public Object[][] unavailableParentThreads()
	{
		return new Object[][]
		{
			{null, null},
			{"", null},
			{THREAD, null},
			{THREAD, "{\"type\":\"turn_context\",\"payload\":{\"multi_agent_version\":\n"},
			{THREAD, "{\"type\":\"session_meta\",\"payload\":{}}\n"}
		};
	}

	/**
	 * Verifies that a session whose parent thread is absent, has no rollout, or has a rollout without a readable state
	 * gets only the parent configuration's entries.
	 *
	 * @param threadId the parent thread's identifier, or {@code null} if no Codex thread starts the session
	 * @param rollout  the parent thread's rollout, or {@code null} if it has none
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "unavailableParentThreads")
	public void unavailableThreadKeepsConfiguration(String threadId, String rollout) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-multi-agent-"));
			Path codexHome = root.resolve("host");
			if (rollout != null)
				writeRollout(codexHome, rollout);

			JsonNode session = session(root, "[features]\nmulti_agent_v2 = true\n",
				CodexParentMultiAgentState.read(JSON, codexHome, threadId), Platform.LINUX_X64);

			requireTable(session, "features", "multi_agent_v2 = true");
			requireTable(session, "agents", "");
		}
	}

	/**
	 * Verifies that a session whose parent thread's rollout cannot be opened gets only the parent configuration's
	 * entries.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void unreadableRolloutKeepsConfiguration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-multi-agent-"));
			Path codexHome = root.resolve("host");
			Path rollout = writeRollout(codexHome, turnContext("disabled") + "\n");
			// Only a file system with POSIX permissions can deny its owner reading a file
			if (PortableFiles.hasModes(rollout))
				Files.setPosixFilePermissions(rollout, Set.of(PosixFilePermission.OWNER_WRITE));
			else
			{
				Files.delete(rollout);
				Files.createDirectories(rollout);
			}

			CodexParentMultiAgentState state = CodexParentMultiAgentState.read(JSON, codexHome, THREAD);
			JsonNode session = session(root, "[features]\nmulti_agent_v2 = true\n", state, Platform.LINUX_X64);

			requireTable(session, "features", "multi_agent_v2 = true");
			requireTable(session, "agents", "");
		}
	}

	/**
	 * Writes the parent configuration and returns the session configuration that the runner writes from it.
	 *
	 * @param root        the fixture directory
	 * @param parent      the parent configuration
	 * @param parentState the parent thread's recorded state
	 * @param platform    the platform that the session runs on
	 * @return the session configuration's values
	 * @throws IOException if fixture I/O fails
	 */
	private static JsonNode session(Path root, String parent, CodexParentMultiAgentState parentState,
		Platform platform) throws IOException
	{
		Path source = Files.writeString(root.resolve("host.toml"), parent, UTF_8);
		Path destination = root.resolve("isolated/config.toml");
		CodexConnectionConfiguration.copy(source, destination, root.resolve("project"), HarnessDependencyCaches.NONE,
			root.resolve("isolated/tmp"), 0, null, platform, parentState);
		return TOML.readTree(Files.readString(destination, UTF_8));
	}

	/**
	 * Requires a configuration to hold a table with exactly the given entries.
	 *
	 * @param configuration the configuration's values
	 * @param name          the table's name
	 * @param entries       the table's expected entries in TOML, or empty if the configuration must not hold the table
	 */
	private static void requireTable(JsonNode configuration, String name, String entries)
	{
		if (entries.isBlank())
		{
			requireThat(configuration.has(name), name + "Written").isFalse();
			return;
		}
		requireThat(configuration.path(name), name).isEqualTo(TOML.readTree(entries));
	}

	/**
	 * Writes the rollout of the parent thread.
	 *
	 * @param codexHome the parent's Codex home
	 * @param content   the rollout's content
	 * @return the rollout
	 * @throws IOException if fixture I/O fails
	 */
	private static Path writeRollout(Path codexHome, String content) throws IOException
	{
		Path day = Files.createDirectories(codexHome.resolve("sessions/2026/11/02"));
		return Files.writeString(day.resolve("rollout-2026-11-02T10-00-00-" + THREAD + ".jsonl"), content, UTF_8);
	}

	/**
	 * Returns a {@code turn_context} record.
	 *
	 * @param version the recorded multi-agent version
	 * @return the record
	 */
	private static String turnContext(String version)
	{
		return "{\"timestamp\":\"2026-11-02T10:00:00.000Z\",\"type\":\"turn_context\",\"payload\":" +
			"{\"turn_id\":\"t\",\"model\":\"gpt-6-sol\",\"multi_agent_version\":\"" + version + "\"}}";
	}

	/**
	 * Returns a {@code world_state} record that holds a multi-agent mode.
	 *
	 * @param full whether the record replaces the whole state
	 * @param mode the recorded mode in JSON
	 * @return the record
	 */
	private static String worldState(boolean full, String mode)
	{
		return "{\"timestamp\":\"2026-11-02T10:00:00.001Z\",\"type\":\"world_state\",\"payload\":{\"full\":" + full +
			",\"state\":{\"environment\":{\"cwd\":\"/p\"},\"multi_agent_mode\":{\"mode\":" + mode +
			",\"usage_hint_hash\":\"a\"}}}}";
	}
}
