package com.holdmyspot.cat.claude.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.ClaudeSessionEndHook;
import com.holdmyspot.cat.claude.ClaudeSessionStartHook;
import com.holdmyspot.cat.claude.ClaudeSubagentStartHook;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.TestToolScope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import com.holdmyspot.cat.common.fakes.test.Yielding;

/**
 * Tests {@link ClaudeSessionStartHook}.
 */
public final class ClaudeSessionStartHookTest
{
	private static final String SESSION_ID = "6b1f7c1e-5d0a-4c55-9a8e-2f6c0f1d2e3a";
	private static final String CAT_TEMP_RULE = "`CAT_TEMP` is `${CAT_PROJECT_ROOT}/.cat/sessions/" +
		SessionDirectory.SESSION_ID_DIRECTIVE + "/temp`.\n";

	/**
	 * Verifies Claude receives a short instruction that locates the complete main-agent rules in CAT's temporary root and
	 * requires reading that whole file rather than a searched subset.
	 */
	@Test
	public void injectsClaudeRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/common.md", "plugin common");
			write(plugin, "rules/claude/claude.md", "plugin claude");
			write(plugin, "rules/codex/codex.md", "plugin codex");
			write(project, ".cat/rules/common/common.md", "project common");
			write(project, ".cat/rules/claude/claude.md", "project claude");
			write(project, ".cat/rules/codex/codex.md", "project codex");

			HookResult result = invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()),
				project);
			String instruction = context(result);
			Path rules = referencedFile(instruction);

			requireThat(instruction, "instruction").startsWith("# CAT SessionStart Context").
				contains("Read the whole file").contains("with the Read tool").
				contains("Do not search it, list its headings, or read only part of it").doesNotContain("plugin common").
				doesNotContain("plugin claude").doesNotContain("project common").doesNotContain("project claude").
				doesNotContain("plugin codex");
			requireThat(rules, "rules").isEqualTo(project.resolve(".cat/sessions").resolve(sessionId(rules)).
				resolve("rules.md"));
			try
			{
				String completeRules = Files.readString(rules);
				requireThat(completeRules, "completeRules").contains("plugin common").contains("plugin claude").
					contains("project common").contains("project claude").doesNotContain("plugin codex");
			}
			finally
			{
				Files.deleteIfExists(rules);
			}
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies Claude injects a user rule derived from its harness configuration directory without placing it in a
	 * project.
	 */
	@Test
	public void injectsUserRulesOutsideTheProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path userHome = cleanup.add(Files.createTempDirectory("cat-user-home-"));
			Path configuration = userHome.resolve(".claude");
			write(userHome, ".cat/rules/common/personal.md", "personal guidance");

			HookResult result = invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(),
				"CLAUDE_CONFIG_DIR", configuration.toString(), Platform.current().homeVariable(), userHome.toString()),
				project);

			requireThat(completeContext(result), "context").contains("personal guidance");
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies a session that its runner lets start sessions of its own still receives CAT's rules.
	 */
	@Test
	public void loadsRulesWhenSessionsMayBeRequested() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path requests = cleanup.add(Files.createTempDirectory("cat-requests-"));
			write(plugin, "rules/common/rule.md", "rule");

			HookResult result = invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(),
				"CAT_SESSION_REQUESTS", requests.toString(),
				"CAT_REQUEST_SESSION", plugin.resolve("client/bin/request-session").toString()), project);

			requireThat(completeContext(result), "context").contains("rule");
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies CAT installation variables must agree with Claude's harness plugin root and cannot override it.
	 */
	@Test
	public void rejectsConflictingInstallationDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path conflictingPlugin = cleanup.add(Files.createTempDirectory("cat-conflicting-plugin-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/rule.md", "rule");

			HookResult matching = invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(),
				"CAT_INSTALL_DIR", plugin.toString()), project);
			HookResult conflicting = invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(),
				"CAT_INSTALL_DIR", conflictingPlugin.toString()), project);
			HookResult unsupported = invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(),
				"CAT_PROJECT_DIR", project.toString()), project);

			requireThat(completeContext(matching), "matchingContext").contains("rule");
			requireThat(matching.error(), "matchingError").isEmpty();
			requireThat(conflicting.output(), "conflictingOutput").isEqualTo("{}\n");
			requireThat(conflicting.error(), "conflictingError").
				contains("CAT_INSTALL_DIR conflicts with Claude CLAUDE_PLUGIN_ROOT");
			requireThat(unsupported.output(), "unsupportedOutput").isEqualTo("{}\n");
			requireThat(unsupported.error(), "unsupportedError").
				contains("CAT_PROJECT_DIR conflicts with Claude CLAUDE_PLUGIN_ROOT");
		}
	}

	/**
	 * Verifies SessionEnd removes the rule context owned by its session.
	 */
	@Test
	public void deletesContextWhenSessionEnds() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/rules.md", "rules");
			Path context = referencedFile(context(invoke(cwdInput(project),
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project)));

			String error = invokeSessionEnd("{\"session_id\":\"" + sessionId(context) + "\"}",
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project);

			requireThat(Files.exists(context), "contextExists").isFalse();
			requireThat(error, "error").isEmpty();
		}
	}

	/**
	 * Verifies one resumable session retains its context until it ends and that ending another session cannot remove it.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void preservesOtherResumableSessionContext() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/rules.md", "rules");
			String firstSession = UUID.randomUUID().toString();
			String secondSession = UUID.randomUUID().toString();
			Map<String, String> environment = Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString());
			Path first = referencedFile(context(invoke("{\"cwd\":" + jsonString(project) + ",\"session_id\":\"" +
				firstSession + "\"}", environment, project)));
			Path second = referencedFile(context(invoke("{\"cwd\":" + jsonString(project) + ",\"session_id\":\"" +
				secondSession + "\"}", environment, project)));

			requireThat(Files.exists(first), "firstBeforeSessionEnd").isTrue();
			requireThat(Files.exists(second), "secondBeforeSessionEnd").isTrue();
			String error = invokeSessionEnd("{\"session_id\":\"" + firstSession + "\"}", environment, project);

			requireThat(Files.exists(first), "firstAfterSessionEnd").isFalse();
			requireThat(Files.exists(second), "secondAfterOtherSessionEnd").isTrue();
			requireThat(error, "error").isEmpty();
		}
	}

	/**
	 * Verifies SessionEnd also removes context owned by a subagent in its session.
	 */
	@Test
	public void deletesSubagentContextWhenSessionEnds() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			write(plugin, "rules/common/rules.md", "rules");
			Path context = referencedFile(context(invokeSubagent(cwdInput(project),
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project)));
			requireThat(context.getFileName().toString(), "contextName").isEqualTo("agent-test.md");
			requireThat(context.getParent().getFileName().toString(), "contextParent").isEqualTo("subagents");

			String error = invokeSessionEnd("{\"session_id\":\"" + sessionId(context.getParent()) + "\"}",
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project);

			requireThat(Files.exists(context), "contextExists").isFalse();
			requireThat(error, "error").isEmpty();
		}
	}

	/**
	 * Verifies path-scoped rules remain lazy and the hook event's directory takes precedence over configuration.
	 */
	@Test
	public void keepsRulesLazyAndPrefersHarnessCwd() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuredProject = cleanup.add(Files.createTempDirectory("cat-configured-project-"));
			Path harnessProject = cleanup.add(Files.createTempDirectory("cat-harness-project-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			write(configuredProject, ".cat/rules/common/rule.md", "configured project");
			write(harnessProject, ".cat/rules/claude/path.md", "---\npaths: [\"*.java\"]\n---\nproject Java rule");
			write(harnessProject, ".cat/rules/claude/rule.md", "harness project");

			String context = completeContext(invoke(cwdInput(harnessProject),
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), harnessProject));

			requireThat(context, "context").contains("harness project").
				contains("Load `" + harnessProject.resolve(".cat/rules/claude/path.md") +
					"` only when you read or edit a file inside the project `${CAT_PROJECT_ROOT}" +
					"` whose path relative to it matches [*.java].").
				doesNotContain("configured project").
				doesNotContain("project Java rule");
		}
	}

	/**
	 * Verifies the harness SubagentStart hook injects only rules addressed to the spawned agent type.
	 */
	@Test
	public void injectsRulesForSubagentAudience() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			write(plugin, "rules/common/main.md", "---\nagents: [\"main\"]\n---\nmain guidance");
			write(plugin, "rules/common/subagents.md", "---\nagents: [\"subagents\"]\n---\nsubagent guidance");
			write(plugin, "rules/common/reviewer.md", "---\nagents: [\"cat:reviewer\"]\n---\nreviewer guidance");

			String reviewer = completeContext(invokeSubagent("{\"cwd\":" + jsonString(project) +
				",\"agent_type\":\"cat:reviewer\"}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project));
			String explorer = completeContext(invokeSubagent("{\"cwd\":" + jsonString(project) +
				",\"agent_type\":\"Explore\"}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project));

			requireThat(reviewer, "reviewer").contains("subagent guidance").contains("reviewer guidance").
				doesNotContain("main guidance");
			requireThat(explorer, "explorer").contains("subagent guidance").doesNotContain("reviewer guidance").
				doesNotContain("main guidance");
		}
	}

	/**
	 * Verifies Claude receives bundled lazy rule paths below the {@code ${CAT_PLUGIN_ROOT}} notation, which its skill
	 * environment rule resolves, instead of absolute paths or references relative to removed rule sources.
	 */
	@Test
	public void resolvesBundledLazyRuleReferences() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path lazyRule = plugin.resolve("rules/include/java/javadoc.md");
			write(plugin, "rules/common/rule-routing.md", "Before editing Java, lazy load `../include/java/javadoc.md`.\n");
			write(plugin, "rules/include/java/javadoc.md", "Javadoc guidance\n");

			String context = completeContext(invoke(cwdInput(project),
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project));

			requireThat(context, "context").contains("`${CAT_PLUGIN_ROOT}/rules/include/java/javadoc.md`").
				doesNotContain(lazyRule.toString()).doesNotContain("`../include/java/javadoc.md`");
		}
	}

	/**
	 * Verifies the hook creates the session's CAT session directory in the directory that the session runs in, which the
	 * injected rules name as the project directory, even when an enclosing directory is a CAT project, and that it no
	 * longer creates the shared temporary directory.
	 */
	@Test
	public void createsSessionDirectoryInLaunchDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path parent = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Files.createDirectories(parent.resolve(".cat"));
			Path session = Files.createDirectories(parent.resolve("module"));

			HookResult result = invoke("{\"cwd\":" + jsonString(session) + ",\"session_id\":\"" + SESSION_ID + "\"}",
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), session);

			requireThat(result.error(), "error").isEmpty();
			requireThat(session.resolve(".cat/sessions/" + SESSION_ID + "/temp"), "catTemp").isDirectory();
			requireThat(Files.exists(session.resolve(".cat/work/temp")), "sharedTempExists").isFalse();
			requireThat(Files.exists(parent.resolve(".cat/sessions")), "parentSessionsRootExists").isFalse();
		}
	}

	/**
	 * Verifies SessionStart writes the main agent's rules to the CAT session directory that the input's
	 * {@code session_id} names, creates its {@code temp} directory and marker, and states that directory as
	 * {@code CAT_TEMP} in the rules.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void writesRulesToSessionDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path configuration = project.resolve("claude-configuration");
			write(plugin, "rules/claude/cat-env.md", CAT_TEMP_RULE);
			Path directory = project.resolve(".cat/sessions").resolve(SESSION_ID);

			HookResult result = invoke("{\"cwd\":" + jsonString(project) + ",\"session_id\":\"" + SESSION_ID +
				"\",\"source\":\"startup\"}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(), "CLAUDE_CONFIG_DIR",
				configuration.toString()), project);
			Path rules = referencedFile(context(result));

			requireThat(result.error(), "error").isEmpty();
			requireThat(rules, "rules").isEqualTo(directory.resolve("rules.md"));
			requireThat(directory.resolve("temp"), "temp").isDirectory();
			requireThat(Files.readString(rules), "rulesContent").
				contains("`CAT_TEMP` is `${CAT_PROJECT_ROOT}/.cat/sessions/" + SESSION_ID + "/temp`.");
			try (ToolScope scope = new TestToolScope())
			{
				JsonNode marker = scope.json().readTree(Files.readString(directory.resolve("session.json")));
				requireThat(marker.path("harness").asString(), "harness").isEqualTo("claude");
				requireThat(marker.path("configuration_directory").asString(), "configurationDirectory").
					isEqualTo(configuration.toString());
				requireThat(marker.path("transcripts").get(0).asString(), "transcript").
					isEqualTo("projects/*/" + SESSION_ID + ".jsonl");
			}
		}
	}

	/**
	 * Verifies SubagentStart writes a subagent's rules to its own file in the CAT session directory of the parent
	 * session, whose ID the input's {@code session_id} carries, and states that directory as {@code CAT_TEMP} in the
	 * rules.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void writesSubagentRulesToSessionDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			write(plugin, "rules/claude/cat-env.md", CAT_TEMP_RULE);
			Path directory = project.resolve(".cat/sessions").resolve(SESSION_ID);

			HookResult result = invokeSubagent("{\"cwd\":" + jsonString(project) + ",\"session_id\":\"" + SESSION_ID +
				"\",\"agent_id\":\"a1b2c3\"}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project);
			Path rules = referencedFile(context(result));

			requireThat(result.error(), "error").isEmpty();
			requireThat(rules, "rules").isEqualTo(directory.resolve("subagents/a1b2c3.md"));
			requireThat(directory.resolve("session.json"), "marker").isRegularFile();
			requireThat(Files.readString(rules), "rulesContent").
				contains("`CAT_TEMP` is `${CAT_PROJECT_ROOT}/.cat/sessions/" + SESSION_ID + "/temp`.");
		}
	}

	/**
	 * Verifies SessionStart refuses a {@code session_id} that is not a UUID in canonical form without writing rules or a
	 * CAT session directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesNonCanonicalSessionId() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));

			HookResult result = invoke("{\"cwd\":" + jsonString(project) + ",\"session_id\":\"" +
				SESSION_ID.toUpperCase(Locale.ROOT) + "\"}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project);

			requireThat(result.output(), "output").isEqualTo("{}\n");
			requireThat(result.error(), "error").contains("not a UUID in canonical form");
			requireThat(Files.exists(project.resolve(".cat/sessions")), "sessionsRootExists").isFalse();
		}
	}

	/**
	 * Verifies SessionStart removes the CAT session directory of another session whose marker is older than a day and
	 * whose transcript the configuration directory no longer holds, and keeps the directory of a session whose transcript
	 * remains.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void sweepsGoneSessions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path configuration = project.resolve("claude-configuration");
			String gone = "11111111-1111-4111-8111-111111111111";
			String kept = "22222222-2222-4222-8222-222222222222";
			write(configuration, "projects/-project/" + kept + ".jsonl", "{}\n");
			Path goneDirectory = writeOldMarker(project, gone, configuration);
			Path keptDirectory = writeOldMarker(project, kept, configuration);

			HookResult result = invoke("{\"cwd\":" + jsonString(project) + ",\"session_id\":\"" + SESSION_ID +
				"\",\"source\":\"resume\"}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(), "CLAUDE_CONFIG_DIR",
				configuration.toString()), project);

			requireThat(result.error(), "error").isEmpty();
			requireThat(Files.exists(goneDirectory), "goneExists").isFalse();
			requireThat(keptDirectory, "keptDirectory").isDirectory();
			requireThat(project.resolve(".cat/sessions").resolve(SESSION_ID), "ownDirectory").isDirectory();
		}
	}

	/**
	 * Returns the {@code source} values of a SessionStart.
	 *
	 * @return one row per source
	 */
	@DataProvider
	public static Object[][] sources()
	{
		return new Object[][] {{"startup"}, {"resume"}};
	}

	/**
	 * Verifies SessionStart moves every CAT session directory below {@code .cat/work} that holds a marker, the session's
	 * own and another session's, to {@code .cat/sessions} with its campaign files, keeps the own directory's marker, and
	 * leaves a directory without a marker and {@code .cat/work/temp} in place.
	 *
	 * @param source the SessionStart source
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(dataProvider = "sources")
	public void movesFormerSessionDirectories(String source) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path configuration = project.resolve("claude-configuration");
			String other = "33333333-3333-4333-8333-333333333333";
			String unmarked = "44444444-4444-4444-8444-444444444444";
			String ownMarker = writeFormerDirectory(project, SESSION_ID, configuration);
			writeFormerDirectory(project, other, configuration);
			Path unmarkedDirectory = Files.createDirectories(project.resolve(".cat/work/" + unmarked + "/temp"));
			Path shared = Files.createDirectories(project.resolve(".cat/work/temp"));

			HookResult result = invoke("{\"cwd\":" + jsonString(project) + ",\"session_id\":\"" + SESSION_ID +
				"\",\"source\":\"" + source + "\"}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(), "CLAUDE_CONFIG_DIR",
				configuration.toString()), project);

			requireThat(result.error(), "error").isEmpty();
			Path sessions = project.resolve(".cat/sessions");
			requireThat(Files.readString(sessions.resolve(SESSION_ID + "/session.json"), UTF_8), "ownMarker").
				isEqualTo(ownMarker);
			for (String sessionId : new String[] {SESSION_ID, other})
			{
				requireThat(Files.readString(sessions.resolve(sessionId + "/optimizer/c1/campaign.json"), UTF_8),
					"campaign").isEqualTo("{}");
				requireThat(Files.exists(project.resolve(".cat/work/" + sessionId)), "formerExists").isFalse();
			}
			requireThat(unmarkedDirectory, "unmarkedDirectory").isDirectory();
			requireThat(shared, "shared").isDirectory();
			requireThat(Files.exists(sessions.resolve(unmarked)), "unmarkedMoved").isFalse();
		}
	}

	/**
	 * Verifies SessionStart leaves a CAT session directory below {@code .cat/work} in place, and the directory at its new
	 * path unchanged, when the new path exists, reports both paths on standard error, and still injects the rules.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void keepsFormerDirectoryWhenNewPathExists() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path configuration = project.resolve("claude-configuration");
			String other = "33333333-3333-4333-8333-333333333333";
			writeFormerDirectory(project, other, configuration);
			Path current = Files.createDirectories(project.resolve(".cat/sessions/" + other));
			Files.writeString(current.resolve("rules.md"), "current rules", UTF_8);

			HookResult result = invoke("{\"cwd\":" + jsonString(project) + ",\"session_id\":\"" + SESSION_ID +
				"\",\"source\":\"startup\"}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(), "CLAUDE_CONFIG_DIR",
				configuration.toString()), project);

			Path former = project.resolve(".cat/work/" + other);
			requireThat(former.resolve("optimizer/c1/campaign.json"), "formerCampaign").isRegularFile();
			requireThat(Files.readString(current.resolve("rules.md"), UTF_8), "currentRules").isEqualTo("current rules");
			requireThat(Files.exists(current.resolve("session.json")), "currentMarkerExists").isFalse();
			requireThat(result.error().lines().toList(), "errorLines").size().isEqualTo(1);
			requireThat(result.error(), "error").contains(former.toString()).contains(current.toString());
			requireThat(referencedFile(context(result)), "rules").
				isEqualTo(project.resolve(".cat/sessions/" + SESSION_ID + "/rules.md"));
		}
	}

	/**
	 * Verifies that the directory Claude started in stays the project directory after the agent changes into a
	 * subdirectory, so the hooks neither write a context file into the subdirectory nor miss it when the session ends.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void keepsLaunchDirectoryAfterCd() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			write(project, ".cat/rules/common/rule.md", "launch project");
			Path module = Files.createDirectories(project.resolve("module"));
			Map<String, String> environment = Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(),
				"CLAUDE_PROJECT_DIR", project.toString());

			HookResult result = invoke(cwdInput(module), environment, module);
			Path context = referencedFile(context(result));

			requireThat(result.error(), "error").isEmpty();
			requireThat(Files.readString(context), "context").contains("launch project");
			requireThat(context.startsWith(project.resolve(".cat/sessions")), "contextInLaunchDirectory").isTrue();
			requireThat(Files.exists(module.resolve(".cat")), "moduleCatExists").isFalse();

			String error = invokeSessionEnd("{\"cwd\":" + jsonString(module) + ",\"session_id\":\"" + sessionId(context) +
				"\"}",
				environment, module);

			requireThat(error, "sessionEndError").isEmpty();
			requireThat(Files.exists(context), "contextExists").isFalse();
		}
	}

	/**
	 * Verifies that the bundled Claude rules that SessionStart writes state the directory Claude started in, and that
	 * directory's session temporary directory, as literal paths, even when the hook runs in a subdirectory that the
	 * agent changed into, so the agent never takes either directory from its own working directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void statesLaunchDirectoryInSessionRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-")).toAbsolutePath().normalize();
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			copyBundledRules(plugin);
			Path module = Files.createDirectories(project.resolve("module"));

			HookResult result = invoke("{\"cwd\":" + jsonString(module) + ",\"session_id\":\"" + SESSION_ID + "\"}",
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(), "CLAUDE_PROJECT_DIR", project.toString()), module);

			requireThat(result.error(), "error").isEmpty();
			assertStatesProjectDirectories(Files.readString(referencedFile(context(result))), project, module);
		}
	}

	/**
	 * Verifies that the bundled Claude rules that SessionStart writes state the user rule directory as a literal path
	 * when the user has one, and do not mention it when the user has none.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void statesUserRulesInSessionRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-")).toAbsolutePath().normalize();
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path userHome = cleanup.add(Files.createTempDirectory("cat-user-home-")).toAbsolutePath().normalize();
			Path emptyHome = cleanup.add(Files.createTempDirectory("cat-empty-home-"));
			copyBundledRules(plugin);
			write(userHome, ".cat/rules/common/personal.md", "personal guidance");
			String homeVariable = Platform.current().homeVariable();

			String withUserRules = completeContext(invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT",
				plugin.toString(), "CLAUDE_CONFIG_DIR", userHome.resolve(".claude").toString(), homeVariable,
				userHome.toString()), project));
			String withoutUserRules = completeContext(invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT",
				plugin.toString(), "CLAUDE_CONFIG_DIR", emptyHome.resolve(".claude").toString(), homeVariable,
				emptyHome.toString()), project));

			requireThat(withUserRules, "withUserRules").
				contains("`CAT_USER_RULES` is `" + userHome.resolve(".cat/rules") + "`").
				doesNotContain(ProjectRuleLoader.USER_RULES_DIRECTIVE);
			requireThat(withoutUserRules, "withoutUserRules").doesNotContain("CAT_USER_RULES").
				doesNotContain(ProjectRuleLoader.USER_RULES_DIRECTIVE).contains("`CAT_PROJECT_ROOT` is `" + project + "`");
		}
	}

	/**
	 * Verifies that the bundled Claude rules that SubagentStart writes state the directory Claude started in, and that
	 * directory's session temporary directory, as literal paths, even when the hook runs in a subdirectory that the
	 * agent changed into.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void statesLaunchDirectoryInSubagentRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-")).toAbsolutePath().normalize();
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			copyBundledRules(plugin);
			Path module = Files.createDirectories(project.resolve("module"));

			HookResult result = invokeSubagent("{\"cwd\":" + jsonString(module) + ",\"session_id\":\"" + SESSION_ID +
				"\",\"agent_id\":\"a1b2c3\"}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(), "CLAUDE_PROJECT_DIR",
				project.toString()), module);

			requireThat(result.error(), "error").isEmpty();
			assertStatesProjectDirectories(Files.readString(referencedFile(context(result))), project, module);
		}
	}

	/**
	 * Verifies that on Windows the bundled Claude rules, for the main agent and for a subagent, state the session's
	 * temporary directory with Windows separators throughout, the form in which Windows writes the path of the
	 * directory that the hook creates, and tell the agent to quote that path in the Git Bash commands that Claude Code
	 * runs on Windows, because Git Bash drops each backslash of an unquoted path.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void statesWindowsTemporaryDirectoryOnWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new TestToolScope())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-")).toAbsolutePath().normalize();
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			copyBundledRules(plugin);
			ProjectRuleLoader loader = new ProjectRuleLoader(scope.yaml(), Platform.WINDOWS_X64);
			String temp = project + "\\.cat\\sessions\\" + SessionDirectory.SESSION_ID_DIRECTIVE + "\\temp";

			for (String rules : List.of(loader.loadForMainAgent(plugin, null, project, "claude"),
				loader.loadForSubagent(plugin, null, project, "claude", "worker")))
			{
				requireThat(rules, "rules").contains("`CAT_PROJECT_ROOT` is `" + project + "`").
					contains("`CAT_TEMP` is `" + temp + "`").contains("`mktemp -d -p '${CAT_TEMP}' ").
					doesNotContain("/.cat/sessions/");
			}
		}
	}

	/**
	 * Copies the Claude rules that CAT bundles into a plugin directory.
	 *
	 * @param plugin the plugin directory
	 * @throws IOException if the rules cannot be copied
	 */
	private static void copyBundledRules(Path plugin) throws IOException
	{
		Path source = Path.of("../rules/src/main/resources/rules/claude");
		Path target = plugin.resolve("rules/claude");
		try (Stream<Path> files = Files.walk(source))
		{
			for (Path file : files.toList())
			{
				Path copy = target.resolve(source.relativize(file).toString());
				if (Files.isDirectory(file))
					Files.createDirectories(copy);
				else
					Files.copy(file, copy);
			}
		}
	}

	/**
	 * Requires that rules state a project directory and its session temporary directory as literal paths, that the
	 * temporary directory exists, and that the rules neither define the project directory as the agent's working
	 * directory nor name the subdirectory that the hook ran in.
	 *
	 * @param rules the rendered rules
	 * @param project the project directory that the hook resolved
	 * @param module the subdirectory that the hook ran in
	 * @throws IOException if the temporary directory cannot be inspected
	 */
	private static void assertStatesProjectDirectories(String rules, Path project, Path module) throws IOException
	{
		Path temp = project.resolve(".cat/sessions").resolve(SESSION_ID).resolve("temp");
		requireThat(rules, "rules").contains("`CAT_PROJECT_ROOT` is `" + project + "`").
			contains("`CAT_TEMP` is `" + temp + "`").
			doesNotContain("working directory that your environment information states").
			doesNotContain(module.toString());
		requireThat(temp, "temp").isDirectory();
	}

	/**
	 * Verifies that a {@code CLAUDE_PROJECT_DIR} that is not a directory fails the hook safely.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsMissingLaunchDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));

			HookResult result = invoke(cwdInput(project), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(),
				"CLAUDE_PROJECT_DIR", project.resolve("missing").toString()), project);

			requireThat(result.output(), "output").isEqualTo("{}\n");
			requireThat(result.error(), "error").contains("CLAUDE_PROJECT_DIR is not a directory");
			requireThat(Files.exists(project.resolve(".cat")), "projectCatExists").isFalse();
		}
	}

	/**
	 * Verifies Claude receives the hook's plugin root wherever a bundled rule writes the plugin-root directive, while
	 * the {@code ${CAT_PLUGIN_ROOT}} notation stays as written.
	 */
	@Test
	public void statesPluginRootInBundledRules() throws IOException
	{
		// Requirement: R-I5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			write(plugin, "rules/claude/cat-env.md", "The plugin directory is `" +
				ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE + "`. Replace `${CAT_PLUGIN_ROOT}` with it.\n");

			String context = completeContext(invoke(cwdInput(project),
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project));

			requireThat(context, "context").contains("The plugin directory is `" + plugin + "`.").
				contains("`${CAT_PLUGIN_ROOT}`").doesNotContain(ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE);
		}
	}

	/**
	 * Verifies malformed input and unavailable installation directories fail without emitting context.
	 */
	@Test
	public void failsSafelyForInvalidSessionStart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			HookResult malformed = invoke("{", Map.of("CLAUDE_PLUGIN_ROOT", project.toString()), project);
			HookResult missingInstallDirectory = invoke("{}", Map.of("CAT_INSTALL_DIR", project.toString()), project);

			requireThat(malformed.output(), "malformedOutput").isEqualTo("{}\n");
			requireThat(missingInstallDirectory.output(), "missingInstallDirectoryOutput").isEqualTo("{}\n");
			requireThat(malformed.error(), "malformedError").contains("CAT SessionStart:");
			requireThat(missingInstallDirectory.error(), "missingInstallDirectoryError").
				contains("Claude CLAUDE_PLUGIN_ROOT is not set");
		}
	}

	/**
	 * Verifies blank input uses the working directory.
	 */
	@Test
	public void resolvesWorkingProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path workingProject = cleanup.add(Files.createTempDirectory("cat-working-project-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			write(workingProject, ".cat/rules/common/rule.md", "working project");

			String workingContext = completeContext(invoke("", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()),
				workingProject));

			requireThat(workingContext, "workingContext").contains("working project");
		}
	}

	/**
	 * Verifies unavailable project directories and unsafe or oversized rules fail safely.
	 */
	@Test
	public void failsForInvalidProjectAndRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path outsideDirectory = cleanup.add(Files.createTempDirectory("cat-outside-rule-"));
			Path outside = outsideDirectory.resolve("outside.md");
			Path rules = Files.createDirectories(project.resolve(".cat/rules/common"));
			Files.writeString(outside, "outside rule");
			Files.createSymbolicLink(rules.resolve("unsafe.md"), outside);

			HookResult invalidProject = invoke(cwdInput(project.resolve("missing")),
				Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project);
			HookResult unsafeRule = invoke("{}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project);
			Files.delete(rules.resolve("unsafe.md"));
			Files.writeString(rules.resolve("large.md"), "x".repeat(1_048_577));
			HookResult oversizedRule = invoke("{}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project);

			requireThat(invalidProject.output(), "invalidProjectOutput").isEqualTo("{}\n");
			requireThat(unsafeRule.output(), "unsafeRuleOutput").isEqualTo("{}\n");
			requireThat(oversizedRule.output(), "oversizedRuleOutput").isEqualTo("{}\n");
			requireThat(invalidProject.error(), "invalidProjectError").contains("Project directory is not a directory");
			requireThat(unsafeRule.error(), "unsafeRuleError").
				contains("CAT rule must be a regular file, not a symbolic link").
				contains("Replace the symbolic link");
			requireThat(oversizedRule.error(), "oversizedRuleError").contains("CAT rule is too large");
		}
	}

	/**
	 * Verifies oversized input and command-line arguments fail safely.
	 */
	@Test
	public void failsForOversizedInputAndArguments() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			HookResult oversized = invoke("x".repeat(1_048_577), Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()), project);
			HookResult arguments = invoke(new String[]{"unexpected"}, "{}", Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString()),
				project);

			requireThat(oversized.output(), "oversizedOutput").isEqualTo("{}\n");
			requireThat(arguments.output(), "argumentsOutput").isEqualTo("{}\n");
			requireThat(oversized.error(), "oversizedError").contains("input is too large");
			requireThat(arguments.error(), "argumentsError").contains("accepts no arguments");
		}
	}

	/**
	 * Returns the session ID that names the CAT session directory holding a main agent's rules file or a subagents'
	 * directory.
	 *
	 * @param file a rules file or the subagents' directory, directly inside a CAT session directory
	 * @return the session ID
	 */
	private static String sessionId(Path file)
	{
		return file.getParent().getFileName().toString();
	}

	/**
	 * Creates the CAT session directory of another session with a marker that is two days old.
	 *
	 * @param project the project
	 * @param sessionId the other session's ID
	 * @param configuration the configuration directory that the marker names
	 * @return the session's CAT session directory
	 * @throws IOException if the directory cannot be created
	 */
	private static Path writeOldMarker(Path project, String sessionId, Path configuration) throws IOException
	{
		Path directory = Files.createDirectories(project.resolve(".cat/sessions").resolve(sessionId).resolve("temp")).
			getParent();
		Files.writeString(directory.resolve("session.json"), "{\"harness\":\"claude\",\"configuration_directory\":" +
			jsonString(configuration) + ",\"created_at\":\"" + Instant.now().minus(Duration.ofDays(2)) +
			"\",\"transcripts\":[\"projects/*/" + sessionId + ".jsonl\"]}", UTF_8);
		return directory;
	}

	/**
	 * Creates a CAT session directory below {@code .cat/work}, as earlier versions of CAT wrote it, with a marker that
	 * is one hour old and a campaign file.
	 *
	 * @param project the project
	 * @param sessionId the session's ID
	 * @param configuration the configuration directory that the marker names
	 * @return the marker's content
	 * @throws IOException if the directory cannot be created
	 */
	private static String writeFormerDirectory(Path project, String sessionId, Path configuration) throws IOException
	{
		Path directory = project.resolve(".cat/work").resolve(sessionId);
		Files.createDirectories(directory.resolve("temp"));
		write(directory, "optimizer/c1/campaign.json", "{}");
		String marker = "{\"harness\":\"claude\",\"configuration_directory\":" + jsonString(configuration) +
			",\"created_at\":\"" + Instant.now().minus(Duration.ofHours(1)) + "\",\"transcripts\":[\"projects/*/" +
			sessionId + ".jsonl\"]}";
		Files.writeString(directory.resolve("session.json"), marker, UTF_8);
		return marker;
	}

	/**
	 * Writes a UTF-8 rule below a root directory.
	 *
	 * @param root the rule root
	 * @param relativePath the relative rule path
	 * @param content the rule content
	 * @throws IOException if the rule cannot be written
	 */
	private static void write(Path root, String relativePath, String content) throws IOException
	{
		requireThat(root, "root").isNotNull();
		requireThat(relativePath, "relativePath").isNotBlank();
		requireThat(content, "content").isNotNull();
		Path file = root.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	/**
	 * Runs the SessionStart hook without arguments and captures its standard streams.
	 *
	 * @param input the hook input
	 * @param environment the process environment
	 * @param workingDirectory the process working directory
	 * @return the captured standard streams
	 */
	private static HookResult invoke(String input, Map<String, String> environment, Path workingDirectory)
	{
		assert input != null;
		assert environment != null;
		assert workingDirectory != null;
		return invoke(new String[0], input, environment, workingDirectory);
	}

	/**
	 * Runs the SessionStart hook with supplied arguments and captures its standard streams.
	 *
	 * @param args the command-line arguments
	 * @param input the hook input
	 * @param environment the process environment
	 * @param workingDirectory the process working directory
	 * @return the captured standard streams
	 */
	private static HookResult invoke(String[] args, String input, Map<String, String> environment, Path workingDirectory)
	{
		assert args != null;
		assert input != null;
		assert environment != null;
		assert workingDirectory != null;
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		Map<String, String> harnessEnvironment = new HashMap<>(environment);
		harnessEnvironment.putIfAbsent(Platform.current().homeVariable(), workingDirectory.resolve("home").toString());
		ClaudeSessionStartHook.run(args, new ByteArrayInputStream(sessionInput(input).getBytes(UTF_8)), output,
			new PrintStream(error, true, UTF_8), harnessEnvironment, workingDirectory, Yielding.waiting());
		return new HookResult(output.toString(UTF_8), error.toString(UTF_8));
	}

	/**
	 * Runs the SubagentStart hook and captures its standard streams.
	 *
	 * @param input the hook input
	 * @param environment the process environment
	 * @param workingDirectory the process working directory
	 * @return the captured standard streams
	 */
	private static HookResult invokeSubagent(String input, Map<String, String> environment, Path workingDirectory)
	{
		assert input != null;
		assert environment != null;
		assert workingDirectory != null;
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		Map<String, String> harnessEnvironment = new HashMap<>(environment);
		harnessEnvironment.putIfAbsent(Platform.current().homeVariable(), workingDirectory.resolve("home").toString());
		ClaudeSubagentStartHook.run(new String[0], new ByteArrayInputStream(sessionInput(input).getBytes(UTF_8)), output,
			new PrintStream(error, true, UTF_8), harnessEnvironment, workingDirectory, Yielding.waiting());
		return new HookResult(output.toString(UTF_8), error.toString(UTF_8));
	}

	/**
	 * Runs the SessionEnd hook and captures its diagnostic output.
	 *
	 * @param input the hook input
	 * @param environment the process environment
	 * @param workingDirectory the process working directory
	 * @return the diagnostic output
	 */
	private static String invokeSessionEnd(String input, Map<String, String> environment, Path workingDirectory)
	{
		assert input != null;
		assert environment != null;
		assert workingDirectory != null;
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		Map<String, String> harnessEnvironment = new HashMap<>(environment);
		harnessEnvironment.putIfAbsent(Platform.current().homeVariable(), workingDirectory.resolve("home").toString());
		ClaudeSessionEndHook.run(new String[0], new ByteArrayInputStream(input.getBytes(UTF_8)),
			new PrintStream(error, true, UTF_8), harnessEnvironment, workingDirectory, Yielding.waiting());
		return error.toString(UTF_8);
	}

	/**
	 * Adds the common Claude harness session metadata to a valid test event.
	 *
	 * @param input the hook input
	 * @return the input with session metadata when it is a complete JSON object
	 */
	private static String sessionInput(String input)
	{
		assert input != null;
		String sessionId = UUID.randomUUID().toString();
		if (input.isEmpty())
			return "{\"session_id\":\"" + sessionId + "\",\"agent_id\":\"agent-test\"}";
		if (!input.startsWith("{") || !input.endsWith("}") || input.contains("session_id"))
			return input;
		String separator = ",";
		if (input.equals("{}"))
			separator = "";
		return input.substring(0, input.length() - 1) + separator + "\"session_id\":\"" + sessionId +
			"\",\"agent_id\":\"agent-test\"}";
	}

	/**
	 * Extracts the returned SessionStart context.
	 *
	 * @param result the captured hook result
	 * @return the additional context
	 * @throws IOException if the result is not JSON
	 */
	private static String context(HookResult result) throws IOException
	{
		assert result != null;
		try (ToolScope scope = new TestToolScope())
		{
			JsonNode envelope = scope.json().readTree(result.output());
			return envelope.at("/hookSpecificOutput/additionalContext").asString();
		}
	}

	/**
	 * Reads the complete rules from the file named by a successful hook response.
	 *
	 * @param result the hook response
	 * @return the complete rules
	 * @throws IOException if the referenced file cannot be read
	 */
	private static String completeContext(HookResult result) throws IOException
	{
		assert result != null;
		Path file = referencedFile(context(result));
		try
		{
			return Files.readString(file);
		}
		finally
		{
			Files.deleteIfExists(file);
		}
	}

	/**
	 * Extracts the file path enclosed in the instruction's first Markdown code span.
	 *
	 * @param instruction the hook instruction
	 * @return the referenced file
	 */
	private static Path referencedFile(String instruction)
	{
		assert instruction != null;
		int start = instruction.indexOf('`');
		int end = instruction.indexOf('`', start + 1);
		if (start == -1 || end == -1)
			throw new AssertionError("Expected a path in a Markdown code span: " + instruction);
		return Path.of(instruction.substring(start + 1, end));
	}

	/**
	 * Captures the hook's standard streams.
	 *
	 * @param output the standard output
	 * @param error the standard error
	 */
	private record HookResult(String output, String error)
	{
	}

	/**
	 * Returns hook input that names only the session's working directory.
	 *
	 * @param cwd the session's working directory
	 * @return the JSON input
	 */
	private static String cwdInput(Path cwd)
	{
		return "{\"cwd\":" + jsonString(cwd) + "}";
	}

	/**
	 * Returns a path as a JSON string, escaping the separators of a Windows path.
	 *
	 * @param path a path
	 * @return the JSON string, including its quotes
	 */
	private static String jsonString(Path path)
	{
		return "\"" + path.toString().replace("\\", "\\\\") + "\"";
	}
}
