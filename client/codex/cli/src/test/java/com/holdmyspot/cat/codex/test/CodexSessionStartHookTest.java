package com.holdmyspot.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.CodexSessionStartHook;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Verifies Codex SessionStart rule injection.
 */
public final class CodexSessionStartHookTest
{
	private static final String CAT_TEMP_RULE = "`CAT_TEMP` is `${CAT_PROJECT_ROOT}/.cat/sessions/" +
		SessionDirectory.SESSION_ID_DIRECTIVE + "/temp`.\n";

	/**
	 * Verifies unconditional rules are injected while path-scoped rules remain lazy.
	 */
	@Test
	public void injectsDirectAndPathScopedRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path ruleDirectory = Files.createDirectories(project.resolve(".cat/rules/common"));
			Files.writeString(ruleDirectory.resolve("z.md"), "z rule");
			Files.writeString(ruleDirectory.resolve("a.md"), "---\npaths: [\"*.java\"]\n---\njava rule");
			writeReadingGuidance(project);
			HookResult result = invoke(event(project), Map.of("PLUGIN_ROOT", project.toString()),
				Path.of("."));
			String rules = sessionRules(result);
			requireThat(rules, "rules").contains("z rule").
				contains("Load `" + ruleDirectory.resolve("a.md") +
					"` only when you read or edit a file inside the project `${CAT_PROJECT_ROOT}" +
					"` whose path relative to it matches [*.java].").
				doesNotContain("java rule");
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies CAT installation variables must agree with Codex's harness plugin root and cannot override it.
	 */
	@Test
	public void rejectsConflictingInstallationDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			writeReadingGuidance(plugin);
			Path conflictingPlugin = cleanup.add(Files.createTempDirectory("cat-conflicting-plugin-"));
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("rule.md"), "rule");

			HookResult matching = invoke(event(project), Map.of("PLUGIN_ROOT", plugin.toString(),
				"CAT_INSTALL_DIR", plugin.toString()), Path.of("."));
			HookResult conflicting = invoke(event(project), Map.of("PLUGIN_ROOT", plugin.toString(),
				"CAT_INSTALL_DIR", conflictingPlugin.toString()), Path.of("."));
			HookResult unsupported = invoke(event(project), Map.of("PLUGIN_ROOT", plugin.toString(),
				"CAT_PROJECT_DIR", project.toString()), Path.of("."));

			requireThat(sessionRules(matching), "matchingRules").contains("rule");
			requireThat(matching.error(), "matchingError").isEmpty();
			assertStopped(conflicting, "conflicting");
			requireThat(conflicting.error(), "conflictingError").contains("CAT_INSTALL_DIR conflicts with Codex PLUGIN_ROOT");
			assertStopped(unsupported, "unsupported");
			requireThat(unsupported.error(), "unsupportedError").contains("CAT_PROJECT_DIR conflicts with Codex PLUGIN_ROOT");
		}
	}

	/**
	 * Verifies bundled rules precede project rules in the harness-specific order.
	 */
	@Test
	public void injectsAllRuleLocationsInOrder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			writeReadingGuidance(plugin);
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("a.md"), "plugin common");
			Files.writeString(Files.createDirectories(plugin.resolve("rules/codex")).resolve("a.md"), "plugin codex");
			Files.writeString(Files.createDirectories(project.resolve(".cat/rules/common")).resolve("a.md"),
				"project common");
			Files.writeString(Files.createDirectories(project.resolve(".cat/rules/codex")).resolve("a.md"), "project codex");
			String rules = sessionRules(invoke(event(project),
				Map.of("PLUGIN_ROOT", plugin.toString()), Path.of(".")));
			requireThat(rules.indexOf("plugin common"), "pluginCommonIndex").
				isLessThan(rules.indexOf("plugin codex"));
			requireThat(rules.indexOf("plugin codex"), "pluginCodexIndex").
				isLessThan(rules.indexOf("project common"));
			requireThat(rules.indexOf("project common"), "projectCommonIndex").
				isLessThan(rules.indexOf("project codex"));
		}
	}

	/**
	 * Verifies Codex injects a user rule derived from its harness configuration directory without placing it in a
	 * project.
	 */
	@Test
	public void injectsUserRulesOutsideTheProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-project-rules-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			writeReadingGuidance(plugin);
			Path userHome = cleanup.add(Files.createTempDirectory("cat-user-home-"));
			Path codexHome = userHome.resolve(".codex");
			Files.writeString(Files.createDirectories(userHome.resolve(".cat/rules/common")).resolve("personal.md"),
				"personal guidance");

			HookResult result = invoke(event(project), Map.of("PLUGIN_ROOT", plugin.toString(),
				"CODEX_HOME", codexHome.toString(), Platform.current().homeVariable(), userHome.toString()), Path.of("."));

			requireThat(sessionRules(result), "rules").contains("personal guidance");
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies path-scoped bundled rules remain lazy.
	 */
	@Test
	public void keepsPathScopedBundledRulesLazy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			writeReadingGuidance(plugin);
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("path.md"),
				"---\npaths: [\"*.java\"]\n---\nbundled java rule");
			String rules = sessionRules(invoke(event(project),
				Map.of("PLUGIN_ROOT", plugin.toString()), Path.of(".")));
			requireThat(rules, "rules").contains("Load `" + plugin.resolve("rules/common/path.md") +
				"` only when you read or edit a file inside the project `${CAT_PROJECT_ROOT}" +
				"` whose path relative to it matches [*.java].").doesNotContain("bundled java rule");
		}
	}

	/**
	 * Verifies Codex receives bundled lazy rule paths below the {@code ${CAT_PLUGIN_ROOT}} notation, which its skill
	 * environment rule resolves, instead of absolute paths or references relative to removed rule sources.
	 */
	@Test
	public void resolvesBundledLazyRuleReferences() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			writeReadingGuidance(plugin);
			Path lazyRule = plugin.resolve("rules/include/java/javadoc.md");
			Files.writeString(Files.createDirectories(plugin.resolve("rules/common")).resolve("default-rules.md"),
				"Before editing Java, lazy load `../include/java/javadoc.md`.\n");
			Files.createDirectories(lazyRule.getParent());
			Files.writeString(lazyRule, "Javadoc guidance\n");

			String rules = sessionRules(invoke(event(project),
				Map.of("PLUGIN_ROOT", plugin.toString()), Path.of(".")));

			requireThat(rules, "rules").contains("`${CAT_PLUGIN_ROOT}/rules/include/java/javadoc.md`").
				doesNotContain(lazyRule.toString()).doesNotContain("`../include/java/javadoc.md`");
		}
	}

	/**
	 * Verifies Codex receives the hook's plugin root wherever a bundled rule writes the plugin-root directive, while
	 * the {@code ${CAT_PLUGIN_ROOT}} notation stays as written.
	 */
	@Test
	public void statesPluginRootInBundledRules() throws IOException
	{
		// Requirement: R-I5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			writeReadingGuidance(plugin);
			Files.writeString(Files.createDirectories(plugin.resolve("rules/codex")).resolve("cat-env.md"),
				"The plugin directory is `" + ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE + "`. Replace " +
				"`${CAT_PLUGIN_ROOT}` with it.\n");

			String rules = sessionRules(invoke(event(project),
				Map.of("PLUGIN_ROOT", plugin.toString()), Path.of(".")));

			requireThat(rules, "rules").contains("The plugin directory is `" + plugin + "`.").
				contains("`${CAT_PLUGIN_ROOT}`").doesNotContain(ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE);
		}
	}

	/**
	 * Verifies the hook creates the CAT session directory of the session that the input's {@code session_id} names in the
	 * session's project directory, with its {@code temp} directory, so the agent can create a child directory there
	 * without first creating its parent, and a marker that records the harness, its configuration directory and where
	 * the session's transcript is kept. The hook no longer creates the shared temporary directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void createsSessionDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path configuration = project.resolve("codex-home");
			writeReadingGuidance(plugin);
			Path directory = project.resolve(".cat/sessions").resolve(sessionId());

			HookResult result = invoke(event(project), Map.of("PLUGIN_ROOT", plugin.toString(), "CODEX_HOME",
				configuration.toString()), Path.of("."));

			requireThat(result.error(), "error").isEmpty();
			requireThat(directory.resolve("temp"), "temp").isDirectory();
			requireThat(Files.exists(project.resolve(".cat/work/temp")), "sharedTempExists").isFalse();
			JsonNode marker = readJson(Files.readString(directory.resolve("session.json"), UTF_8));
			requireThat(marker.path("harness").asString(), "harness").isEqualTo("codex");
			requireThat(marker.path("configuration_directory").asString(), "configurationDirectory").
				isEqualTo(configuration.toString());
			requireThat(marker.path("transcripts").get(0).asString(), "liveTranscript").
				isEqualTo("sessions/**/rollout-*-" + sessionId() + ".jsonl");
			requireThat(marker.path("transcripts").get(1).asString(), "archivedTranscript").
				isEqualTo("archived_sessions/rollout-*-" + sessionId() + ".jsonl");
		}
	}

	/**
	 * Verifies SessionStart removes the CAT session directory of another session whose marker is older than a day and
	 * whose transcript the configuration directory no longer holds, and keeps the directory of a session whose transcript
	 * is archived.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void sweepsGoneSessions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path configuration = project.resolve("codex-home");
			writeReadingGuidance(plugin);
			String gone = "11111111-1111-4111-8111-111111111111";
			String archived = "22222222-2222-4222-8222-222222222222";
			Path transcript = configuration.resolve("archived_sessions/rollout-2026-10-14T10-00-00-" + archived +
				".jsonl");
			Files.createDirectories(transcript.getParent());
			Files.writeString(transcript, "{}\n", UTF_8);
			Path goneDirectory = writeOldMarker(project, gone, configuration);
			Path archivedDirectory = writeOldMarker(project, archived, configuration);

			HookResult result = invoke(event(project), Map.of("PLUGIN_ROOT", plugin.toString(), "CODEX_HOME",
				configuration.toString()), Path.of("."));

			requireThat(result.error(), "error").isEmpty();
			requireThat(Files.exists(goneDirectory), "goneExists").isFalse();
			requireThat(archivedDirectory, "archivedDirectory").isDirectory();
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
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path configuration = project.resolve("codex-home");
			writeReadingGuidance(plugin);
			String other = "33333333-3333-4333-8333-333333333333";
			String unmarked = "44444444-4444-4444-8444-444444444444";
			String ownMarker = writeFormerDirectory(project, sessionId(), configuration);
			writeFormerDirectory(project, other, configuration);
			Path unmarkedDirectory = Files.createDirectories(project.resolve(".cat/work/" + unmarked + "/temp"));
			Path shared = Files.createDirectories(project.resolve(".cat/work/temp"));

			HookResult result = invoke("{\"session_id\":\"" + sessionId() + "\",\"cwd\":" + jsonString(project) +
				",\"source\":\"" + source + "\"}", Map.of("PLUGIN_ROOT", plugin.toString(), "CODEX_HOME",
				configuration.toString()), Path.of("."));

			requireThat(result.error(), "error").isEmpty();
			Path sessions = project.resolve(".cat/sessions");
			requireThat(Files.readString(sessions.resolve(sessionId() + "/session.json"), UTF_8), "ownMarker").
				isEqualTo(ownMarker);
			for (String sessionId : new String[] {sessionId(), other})
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
	 * path unchanged, when the new path exists, reports both paths on standard error, and still delivers the rules.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void keepsFormerDirectoryWhenNewPathExists() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Path configuration = project.resolve("codex-home");
			writeReadingGuidance(plugin);
			String other = "33333333-3333-4333-8333-333333333333";
			writeFormerDirectory(project, other, configuration);
			Path current = Files.createDirectories(project.resolve(".cat/sessions/" + other));
			Files.writeString(current.resolve("rules.md"), "current rules", UTF_8);

			HookResult result = invoke(event(project), Map.of("PLUGIN_ROOT", plugin.toString(), "CODEX_HOME",
				configuration.toString()), Path.of("."));

			Path former = project.resolve(".cat/work/" + other);
			requireThat(former.resolve("optimizer/c1/campaign.json"), "formerCampaign").isRegularFile();
			requireThat(Files.readString(current.resolve("rules.md"), UTF_8), "currentRules").isEqualTo("current rules");
			requireThat(Files.exists(current.resolve("session.json")), "currentMarkerExists").isFalse();
			requireThat(result.error().lines().toList(), "errorLines").size().isEqualTo(1);
			requireThat(result.error(), "error").contains(former.toString()).contains(current.toString());
			requireThat(context(result), "context").
				contains(project.resolve(".cat/sessions/" + sessionId() + "/rules.md").toString());
		}
	}

	/**
	 * Verifies the hook refuses a {@code session_id} that is not a UUID in canonical form and creates no CAT session
	 * directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void rejectsNonCanonicalSessionId() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			writeReadingGuidance(plugin);

			HookResult result = invoke("{\"session_id\":\"{" + sessionId() + "}\",\"cwd\":" + jsonString(project) + "}",
				Map.of("PLUGIN_ROOT", plugin.toString()), Path.of("."));

			assertStopped(result, "nonCanonical");
			requireThat(result.error(), "error").contains("not a UUID in canonical form");
			requireThat(Files.exists(project.resolve(".cat/sessions")), "sessionsRootExists").isFalse();
		}
	}

	/**
	 * Verifies harness cwd takes precedence over the configured project directory.
	 */
	@Test
	public void prefersHarnessCwd() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuredProject = cleanup.add(Files.createTempDirectory("cat-configured-project-"));
			Path harnessProject = cleanup.add(Files.createTempDirectory("cat-harness-project-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			writeReadingGuidance(plugin);
			Files.writeString(Files.createDirectories(configuredProject.resolve(".cat/rules/common")).resolve("rule.md"),
				"configured project");
			Files.writeString(Files.createDirectories(harnessProject.resolve(".cat/rules/common")).resolve("rule.md"),
				"harness project");

			String rules = sessionRules(invoke(event(harnessProject), Map.of("PLUGIN_ROOT",
				plugin.toString()), Path.of(".")));

			requireThat(rules, "rules").contains("harness project").doesNotContain("configured project");
		}
	}

	/**
	 * Verifies that the bundled Codex rules that SessionStart writes state the project directory that the hook resolved,
	 * and that directory's session temporary directory, as literal paths, even when the hook process runs in a
	 * subdirectory of the project, so the agent never takes either directory from its own working directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void statesProjectDirectoryInSessionRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-")).toAbsolutePath().normalize();
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			copyBundledRules(plugin);
			Path module = Files.createDirectories(project.resolve("module"));

			HookResult result = invoke(event(project), Map.of("PLUGIN_ROOT", plugin.toString()), module);

			requireThat(result.error(), "error").isEmpty();
			assertStatesProjectDirectories(sessionRules(result), project, sessionId(), module);
		}
	}

	/**
	 * Copies the Codex rules that CAT bundles into a plugin directory.
	 *
	 * @param plugin the plugin directory
	 * @throws IOException if the rules cannot be copied
	 */
	static void copyBundledRules(Path plugin) throws IOException
	{
		Path source = Path.of("../rules/src/main/resources/rules/codex");
		Path target = plugin.resolve("rules/codex");
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
	 * Requires that rules state a project directory and a session's temporary directory in it as literal paths, that
	 * the temporary directory exists, and that the rules neither define the project directory through the agent's
	 * environment context nor name the directory that the hook process ran in.
	 *
	 * @param rules the rendered rules
	 * @param project the project directory that the hook resolved
	 * @param sessionId the ID that names the session's CAT session directory
	 * @param module the directory that the hook process ran in
	 * @throws IOException if the temporary directory cannot be inspected
	 */
	static void assertStatesProjectDirectories(String rules, Path project, String sessionId, Path module)
		throws IOException
	{
		Path temp = project.resolve(".cat/sessions").resolve(sessionId).resolve("temp");
		requireThat(rules, "rules").contains("`CAT_PROJECT_ROOT` is `" + project + "`").
			contains("`CAT_TEMP` is `" + temp + "`").
			doesNotContain("`<cwd>` element").
			doesNotContain(module.toString());
		requireThat(temp, "temp").isDirectory();
	}

	/**
	 * Verifies a hook input without {@code cwd} uses the process working directory.
	 */
	@Test
	public void usesWorkingDirectoryWithoutCwd() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			writeReadingGuidance(plugin);
			Files.writeString(Files.createDirectories(project.resolve(".cat/rules/common")).resolve("rule.md"),
				"working directory");

			HookResult result = invoke("{\"session_id\":\"" + sessionId() + "\"}", Map.of("PLUGIN_ROOT",
				plugin.toString()), project);

			requireThat(sessionRules(result), "rules").contains("working directory");
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies the hook writes the session's rules to a file named after the session, and sends only the guidance on
	 * reading a file whole followed by a pointer that tells the agent to read that file whole before anything else.
	 */
	@Test
	public void deliversRulesThroughSessionFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			writeReadingGuidance(plugin);
			Files.writeString(Files.createDirectories(plugin.resolve("rules/codex")).resolve("cat-env.md"),
				"# CAT Environment\n\nEnvironment guidance\n\n" + CAT_TEMP_RULE);
			Files.writeString(Files.createDirectories(project.resolve(".cat/rules/common")).resolve("rule.md"),
				"project guidance");

			HookResult result = invoke(event(project), Map.of("PLUGIN_ROOT", plugin.toString()), Path.of("."));

			Path rulesFile = project.resolve(".cat/sessions").resolve(sessionId()).resolve("rules.md");
			requireThat(context(result), "context").
				startsWith("# Reading Command Output\n\nRun `" + plugin.resolve("bin/run-part") + "`.\n\n# CAT Rules\n\n").
				contains("CAT's rules for this session are in `" + rulesFile + "`. Read that whole file, as described " +
					"above, before you plan, run another command, or respond, even when you could answer the request " +
					"without it, then apply every instruction in it for the rest of this conversation.").
				doesNotContain("Environment guidance").doesNotContain("project guidance");
			requireThat(Files.readString(rulesFile, UTF_8), "rules").startsWith("# CAT Environment").
				contains("project guidance").doesNotContain("# Reading Command Output").
				contains("`CAT_TEMP` is `${CAT_PROJECT_ROOT}/.cat/sessions/" + sessionId() + "/temp`.");
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies that, without the guidance on reading a file whole, the hook sends every rule in its response and writes
	 * no rules file, because the agent would have no instruction on how to read that file whole.
	 */
	@Test
	public void inlinesRulesWithoutReadingGuidance() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			Files.writeString(Files.createDirectories(plugin.resolve("rules/codex")).resolve("cat-env.md"),
				"# CAT Environment\n\nEnvironment guidance\n\n" + CAT_TEMP_RULE);
			Files.writeString(Files.createDirectories(project.resolve(".cat/rules/common")).resolve("rule.md"),
				"project guidance");

			HookResult result = invoke(event(project), Map.of("PLUGIN_ROOT", plugin.toString()), Path.of("."));

			requireThat(context(result), "context").contains("Environment guidance").contains("project guidance").
				doesNotContain("CAT's rules for this session are in").
				contains("`CAT_TEMP` is `${CAT_PROJECT_ROOT}/.cat/sessions/" + sessionId() + "/temp`.");
			requireThat(Files.exists(project.resolve(".cat/sessions").resolve(sessionId()).resolve("rules.md")),
				"rulesFileExists").isFalse();
			requireThat(result.error(), "error").isEmpty();
		}
	}

	/**
	 * Verifies the hook stops the session when the harness omits the session identifier that names its rules file.
	 */
	@Test
	public void rejectsMissingSessionId() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			writeReadingGuidance(plugin);

			HookResult result = invoke("{\"cwd\":" + jsonString(project) + "}", Map.of("PLUGIN_ROOT", plugin.toString()),
				Path.of("."));

			assertStopped(result, "missingSessionId");
			requireThat(result.error(), "error").contains("session_id");
		}
	}

	/**
	 * Verifies malformed input, missing installation directories, and invalid rules fail safely.
	 */
	@Test
	public void failsSafelyForInvalidInputsAndRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			writeReadingGuidance(plugin);
			Files.writeString(Files.createDirectories(project.resolve(".cat/rules/common")).resolve("rule.md"),
				"---\npaths:");

			HookResult malformed = invoke("{", Map.of("PLUGIN_ROOT", plugin.toString()), project);
			HookResult missingInstallDirectory = invoke("{}", Map.of("CAT_INSTALL_DIR", project.toString()), project);
			HookResult invalidRule = invoke(event(project), Map.of("PLUGIN_ROOT", plugin.toString()), project);
			HookResult oversized = invoke("x".repeat(1_048_577), Map.of("PLUGIN_ROOT", plugin.toString()),
				project);

			assertStopped(malformed, "malformed");
			assertStopped(missingInstallDirectory, "missingInstallDirectory");
			assertStopped(invalidRule, "invalidRule");
			assertStopped(oversized, "oversized");
			requireThat(malformed.error(), "malformedError").contains("CAT SessionStart:");
			requireThat(missingInstallDirectory.error(), "missingInstallDirectoryError").
				contains("Codex PLUGIN_ROOT is not set");
			requireThat(invalidRule.error(), "invalidRuleError").contains("Unterminated frontmatter");
			requireThat(oversized.error(), "oversizedError").contains("input is too large");
		}
	}

	/**
	 * Verifies invalid project roots and unsafe or oversized rule files fail safely.
	 */
	@Test
	public void failsForUnsafeRootsAndRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-start-"));
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
			writeReadingGuidance(plugin);
			Path outsideDirectory = cleanup.add(Files.createTempDirectory("cat-outside-rule-"));
			Path outside = outsideDirectory.resolve("outside.md");
			Files.writeString(outside, "outside rule");
			Path rules = Files.createDirectories(project.resolve(".cat/rules/common"));
			Files.createSymbolicLink(rules.resolve("unsafe.md"), outside);

			HookResult invalidProject = invoke(event(project.resolve("missing")),
				Map.of("PLUGIN_ROOT", plugin.toString()), project);
			HookResult unsafeRule = invoke(event(project), Map.of("PLUGIN_ROOT", plugin.toString()), project);
			Files.delete(rules.resolve("unsafe.md"));
			Files.writeString(rules.resolve("large.md"), "x".repeat(1_048_577));
			HookResult oversizedRule = invoke(event(project), Map.of("PLUGIN_ROOT", plugin.toString()), project);

			assertStopped(invalidProject, "invalidProject");
			assertStopped(unsafeRule, "unsafeRule");
			assertStopped(oversizedRule, "oversizedRule");
			requireThat(invalidProject.error(), "invalidProjectError").contains("Project directory is not a directory");
			requireThat(unsafeRule.error(), "unsafeRuleError").
				contains("CAT rule must be a regular file, not a symbolic link").
				contains("Replace the symbolic link");
			requireThat(oversizedRule.error(), "oversizedRuleError").contains("CAT rule is too large");
		}
	}

	/**
	 * Verifies a failed SessionStart response stops the current turn and explains the failure.
	 *
	 * @param result the captured hook result
	 * @param name the assertion-name prefix
	 * @throws IOException if the response is not JSON
	 */
	private static void assertStopped(HookResult result, String name) throws IOException
	{
		JsonNode envelope = readResponse(result);
		requireThat(envelope.has("continue"), name + "ContinueField").isTrue();
		requireThat(envelope.path("continue").asBoolean(), name + "Continue").isFalse();
		requireThat(envelope.path("stopReason").asString(), name + "StopReason").isNotBlank();
		requireThat(envelope.path("systemMessage").asString(), name + "SystemMessage").isNotBlank();
	}

	/**
	 * Runs the SessionStart hook with supplied input and process state, then captures its standard streams.
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
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		Map<String, String> harnessEnvironment = new HashMap<>(environment);
		harnessEnvironment.putIfAbsent(Platform.current().homeVariable(), workingDirectory.resolve("home").toString());
		CodexSessionStartHook.run(new String[0], new ByteArrayInputStream(input.getBytes(UTF_8)), output,
			new PrintStream(error, true, UTF_8), harnessEnvironment, workingDirectory);
		return new HookResult(output.toString(UTF_8), error.toString(UTF_8));
	}

	/**
	 * Returns the Codex session identifier that the test events carry.
	 *
	 * @return a UUID version 7 session identifier
	 */
	private static String sessionId()
	{
		return "019a2b3c-4d5e-7f60-8a1b-2c3d4e5f6a7b";
	}

	/**
	 * Returns a SessionStart event for a session in a project directory.
	 *
	 * @param cwd the session's working directory
	 * @return the event's JSON
	 */
	private static String event(Path cwd)
	{
		assert cwd != null;
		return "{\"session_id\":\"" + sessionId() + "\",\"cwd\":" + jsonString(cwd) + "}";
	}

	/**
	 * Writes the bundled Codex rule on reading a file whole, which every session that keeps its rules in a file needs.
	 *
	 * @param plugin the plugin directory
	 * @throws IOException if the rule cannot be written
	 */
	private static void writeReadingGuidance(Path plugin) throws IOException
	{
		assert plugin != null;
		Files.writeString(Files.createDirectories(plugin.resolve("rules/codex")).resolve("read-file.md"),
			"# Reading Command Output\n\nRun `" + ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE + "/bin/run-part`.\n");
	}

	/**
	 * Returns the rules that the hook wrote to the file its returned context names.
	 *
	 * @param result the captured hook result
	 * @return the rules file's content
	 * @throws IOException if the result is not JSON or its context names no readable rules file
	 */
	private static String sessionRules(HookResult result) throws IOException
	{
		assert result != null;
		String context = context(result);
		Matcher pointer = Pattern.compile("CAT's rules for this session are in `([^`]+)`").matcher(context);
		if (!pointer.find())
			throw new IOException("The SessionStart context names no rules file:\n" + context + "\n" + result.error());
		return Files.readString(Path.of(pointer.group(1)), UTF_8);
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
		JsonNode envelope = readResponse(result);
		return envelope.at("/hookSpecificOutput/additionalContext").asString();
	}

	/**
	 * Creates the CAT session directory of another session with a Codex marker that is two days old.
	 *
	 * @param project the project
	 * @param sessionId the other session's ID
	 * @param configuration the configuration directory that the marker names
	 * @return the session's CAT session directory
	 * @throws IOException if the directory cannot be created
	 */
	private static Path writeOldMarker(Path project, String sessionId, Path configuration) throws IOException
	{
		Path directory = project.resolve(".cat/sessions").resolve(sessionId);
		Files.createDirectories(directory.resolve("temp"));
		Files.writeString(directory.resolve("session.json"), "{\"harness\":\"codex\",\"configuration_directory\":" +
			jsonString(configuration) + ",\"created_at\":\"" + Instant.now().minus(Duration.ofDays(2)) +
			"\",\"transcripts\":[\"sessions/**/rollout-*-" + sessionId + ".jsonl\",\"archived_sessions/rollout-*-" +
			sessionId + ".jsonl\"]}", UTF_8);
		return directory;
	}

	/**
	 * Creates a CAT session directory below {@code .cat/work}, as earlier versions of CAT wrote it, with a Codex marker
	 * that is one hour old and a campaign file.
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
		Path campaign = Files.createDirectories(directory.resolve("optimizer/c1")).resolve("campaign.json");
		Files.writeString(campaign, "{}", UTF_8);
		String marker = "{\"harness\":\"codex\",\"configuration_directory\":" + jsonString(configuration) +
			",\"created_at\":\"" + Instant.now().minus(Duration.ofHours(1)) + "\",\"transcripts\":[\"sessions/**/rollout-*-" +
			sessionId + ".jsonl\"]}";
		Files.writeString(directory.resolve("session.json"), marker, UTF_8);
		return marker;
	}

	/**
	 * Parses JSON text.
	 *
	 * @param text the JSON text
	 * @return the parsed value
	 */
	private static JsonNode readJson(String text)
	{
		try (ToolScope scope = new TestToolScope())
		{
			return scope.json().readTree(text);
		}
	}

	/**
	 * Parses one harness hook response within its bounded JSON operation.
	 *
	 * @param result the captured hook result
	 * @return the parsed response envelope
	 * @throws IOException if the response is not JSON
	 */
	private static JsonNode readResponse(HookResult result) throws IOException
	{
		assert result != null;
		try (ToolScope scope = new TestToolScope())
		{
			return scope.json().readTree(result.output());
		}
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
