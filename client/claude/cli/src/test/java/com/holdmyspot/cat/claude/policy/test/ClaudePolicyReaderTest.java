package com.holdmyspot.cat.claude.policy.test;

import com.holdmyspot.cat.claude.policy.ClaudePolicyReader;
import com.holdmyspot.cat.claude.policy.ClaudePolicyReading;
import com.holdmyspot.cat.claude.policy.ClaudeSession;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

import static com.holdmyspot.cat.claude.policy.test.ClaudeSessionFixture.write;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that CAT reads a Claude Code session's sandbox policy from its command line and settings as Claude Code
 * applies them, and refuses each source that it cannot read safely.
 */
public final class ClaudePolicyReaderTest
{
	/**
	 * The start of each refusal that the user sees.
	 */
	private static final String REFUSAL = "CAT cannot start the session: it cannot read this Claude Code session's " +
		"sandbox policy safely: ";
	/**
	 * User settings that enable the sandbox and nothing else.
	 */
	private static final String ENABLED = """
		{"sandbox": {"enabled": true}}""";

	/**
	 * Creates the test.
	 */
	public ClaudePolicyReaderTest()
	{
	}

	/**
	 * Returns settings that let commands write a folder.
	 *
	 * @param folder the folder
	 * @return the settings as JSON
	 */
	private static String allowWrite(Path folder)
	{
		return """
			{"sandbox": {"filesystem": {"allowWrite": ["%s"]}}}""".formatted(folder);
	}

	/**
	 * Verifies that a session without sandbox settings, or with the sandbox turned off, is an unsandboxed parent.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void disabledSandboxIsUnsandboxed() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			requireThat(fixture.policy().sandboxed(), "sandboxedWithoutSettings").isFalse();
			fixture.userSettings(ENABLED);
			requireThat(fixture.policy().sandboxed(), "sandboxedWhenEnabled").isTrue();
			fixture.localSettings("""
				{"sandbox": {"enabled": false}}""");
			requireThat(fixture.policy(), "policy").isEqualTo(ParentPolicy.UNSANDBOXED);
		}
	}

	/**
	 * Verifies that a setting that more than one layer gives takes the value of the layer that Claude Code ranks
	 * highest: managed settings, whose later drop-in files override earlier ones, then {@code --settings}, then
	 * local, project and user settings.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void higherLayerSettingWins() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			String bind = """
				{"sandbox": {"enabled": true, "network": {"allowLocalBinding": true}}}""";
			String noBind = """
				{"sandbox": {"network": {"allowLocalBinding": false}}}""";
			fixture.userSettings(bind);
			requireThat(fixture.policy().allowLocalBinding(), "user").isTrue();
			fixture.projectSettings(noBind);
			requireThat(fixture.policy().allowLocalBinding(), "project").isFalse();
			fixture.localSettings(bind);
			requireThat(fixture.policy().allowLocalBinding(), "local").isTrue();
			Path flagFile = write(fixture.root.resolve("flag/settings.json"), noBind);
			fixture.arguments.addAll(List.of("--settings", flagFile.toString()));
			requireThat(fixture.policy().allowLocalBinding(), "flag").isFalse();
			write(fixture.managed.resolve("managed-settings.json"), bind);
			requireThat(fixture.policy().allowLocalBinding(), "managed").isTrue();
			write(fixture.managed.resolve("managed-settings.d/20-late.json"), noBind);
			write(fixture.managed.resolve("managed-settings.d/10-early.json"), bind);
			requireThat(fixture.policy().allowLocalBinding(), "dropIn").isFalse();
		}
	}

	/**
	 * Verifies that the sandbox's lists merge across every layer: managed settings and their drop-in files, the file
	 * of {@code --settings}, and the local, project and user settings.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void sandboxListsMergeAcrossLayers() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			Path root = fixture.root;
			fixture.userSettings("""
				{"sandbox": {"enabled": true, "filesystem": {"allowWrite": ["%s"]},
				  "network": {"allowedDomains": ["user.example.com"]}}}""".formatted(root.resolve("w-user")));
			fixture.projectSettings(allowWrite(root.resolve("w-project")));
			fixture.localSettings(allowWrite(root.resolve("w-local")));
			Path flagFile = write(root.resolve("flag/settings.json"), allowWrite(root.resolve("w-flag")));
			fixture.arguments.addAll(List.of("--settings", flagFile.toString()));
			write(fixture.managed.resolve("managed-settings.json"), """
				{"sandbox": {"filesystem": {"allowWrite": ["%s"]},
				  "network": {"allowedDomains": ["*.managed.example.com"]}}}""".formatted(root.resolve("w-managed")));
			write(fixture.managed.resolve("managed-settings.d/10-drop-in.json"),
				allowWrite(root.resolve("w-drop-in")));

			ParentPolicy policy = fixture.policy();
			requireThat(policy.writeRoots(), "writeRoots").containsAll(List.of(fixture.project,
				root.resolve("w-user"), root.resolve("w-project"), root.resolve("w-local"), root.resolve("w-flag"),
				root.resolve("w-managed"), root.resolve("w-drop-in")));
			requireThat(policy.hosts().allows("user.example.com"), "userHost").isTrue();
			requireThat(policy.hosts().allows("api.managed.example.com"), "managedHost").isTrue();
			requireThat(policy.hosts().allows("other.example.com"), "otherHost").isFalse();
		}
	}

	/**
	 * Verifies that settings that {@code --settings} gives as JSON apply like a settings file.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void inlineSettingsApply() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			Path folder = fixture.root.resolve("w-inline");
			fixture.arguments.addAll(List.of("-p", "a prompt", "--settings", """
				{"sandbox": {"enabled": true, "filesystem": {"allowWrite": ["%s"]}}}""".formatted(folder),
				"--output-format", "text"));
			requireThat(fixture.policy().writeRoots(), "writeRoots").contains(folder);
		}
	}

	/**
	 * Verifies that {@code --setting-sources user} keeps the user's settings and drops the project's.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void settingSourcesUserDropsProject() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			Path root = fixture.root;
			fixture.userSettings("""
				{"sandbox": {"enabled": true, "filesystem": {"allowWrite": ["%s"]}}}""".formatted(
				root.resolve("w-user")));
			fixture.projectSettings(allowWrite(root.resolve("w-project")));
			fixture.localSettings(allowWrite(root.resolve("w-local")));
			fixture.arguments.addAll(List.of("--setting-sources", "user"));

			ParentPolicy policy = fixture.policy();
			requireThat(policy.writeRoots(), "writeRoots").contains(root.resolve("w-user"));
			requireThat(policy.writeRoots(), "writeRoots").doesNotContain(root.resolve("w-project"));
			requireThat(policy.writeRoots(), "writeRoots").doesNotContain(root.resolve("w-local"));
		}
	}

	/**
	 * Verifies that {@code --add-dir} takes every following folder up to the next option, and that each lets
	 * commands write it.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void addDirTakesEveryFolder() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			fixture.userSettings(ENABLED);
			Path first = fixture.root.resolve("first");
			fixture.arguments.addAll(List.of("--add-dir", first.toString(), "../second", "--unknown-after"));
			requireThat(fixture.refusal(), "refusal").contains("--unknown-after");

			fixture.arguments.removeLast();
			fixture.arguments.add("-c");
			ParentPolicy policy = fixture.policy();
			requireThat(policy.writeRoots(), "writeRoots").containsAll(List.of(first,
				fixture.root.resolve("second")));
		}
	}

	/**
	 * Verifies that {@code Read(...)} deny rules of the settings and of {@code --disallowedTools} deny reads, in each
	 * spelling of a rule's path.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void readDenyRulesDenyReads() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			Path secret = write(fixture.project.resolve("deep/er/.env"), "");
			fixture.userSettings("""
				{"sandbox": {"enabled": true, "filesystem": {"denyRead": ["%s"]}},
				 "permissions": {"deny": ["Read(//%s)", "Read(~/keys)", "Read(.env)", "Bash(rm:*)"]}}""".formatted(
				fixture.root.resolve("sandbox-denied"), fixture.root.resolve("absolute").toString().substring(1)));
			fixture.projectSettings("""
				{"permissions": {"deny": ["Read(./private/**)"]}}""");
			fixture.arguments.addAll(List.of("--disallowedTools", "Read(./cli-denied) Edit(./x)"));

			List<Path> readDenials = fixture.policy().readDenials();
			requireThat(readDenials, "readDenials").containsAll(List.of(fixture.root.resolve("sandbox-denied"),
				fixture.root.resolve("absolute"), fixture.home.resolve("keys"), secret,
				fixture.project.resolve("cli-denied")));
			requireThat(readDenials, "readDenials").doesNotContain(fixture.project);
		}
	}

	/**
	 * Verifies how permission rules for the {@code Edit} and {@code WebFetch} tools map onto the sandbox: an
	 * {@code Edit} allow rule lets commands write its path and a deny rule keeps them from it, a
	 * {@code WebFetch(domain:...)} allow rule lets them reach the host and a deny rule removes every allowed pattern
	 * that may match the host.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void editAndWebFetchRulesMap() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			Path editable = fixture.root.resolve("editable");
			fixture.userSettings("""
				{"sandbox": {"enabled": true, "network": {"allowedDomains": ["*.example.com", "example.org"]}},
				 "permissions": {"allow": ["Edit(/%s/**)", "WebFetch(domain:fetch.example.net)"],
				   "deny": ["Edit(./generated)", "WebFetch(domain:bad.example.com)"]}}""".formatted(editable));

			ParentPolicy policy = fixture.policy();
			requireThat(policy.writeRoots(), "writeRoots").contains(editable);
			requireThat(policy.writeDenials(), "writeDenials").contains(fixture.project.resolve("generated"));
			requireThat(policy.hosts().allows("fetch.example.net"), "fetchHost").isTrue();
			requireThat(policy.hosts().allows("example.org"), "allowedHost").isTrue();
			requireThat(policy.hosts().allows("good.example.com"), "overlappingHost").isFalse();
			requireThat(policy.hosts().allows("bad.example.com"), "deniedHost").isFalse();
		}
	}

	/**
	 * Verifies that an option that Claude Code does not list is refused by its name.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void unknownOptionIsRefusedByName() throws IOException
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			fixture.userSettings(ENABLED);
			fixture.arguments.addAll(List.of("-p", "--no-such-option", "value"));
			String refusal = fixture.refusal();
			requireThat(refusal, "refusal").startsWith(REFUSAL);
			requireThat(refusal, "refusal").contains("--no-such-option");
			requireThat(refusal, "refusal").endsWith(".");
		}
	}

	/**
	 * Verifies that a settings file that is not JSON is refused by its path.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void unparseableFileIsRefusedByPath() throws IOException
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			fixture.userSettings(ENABLED);
			Path file = write(fixture.managed.resolve("managed-settings.d/50-broken.json"), "{\"sandbox\": ");
			String refusal = fixture.refusal();
			requireThat(refusal, "refusal").startsWith(REFUSAL);
			requireThat(refusal, "refusal").contains(file.toString());
		}
	}

	/**
	 * Verifies that an empty settings file, such as the one that Claude Code's Linux sandbox leaves in the project
	 * while it hides a local settings file that does not exist, holds no settings rather than being refused.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void emptyFileHoldsNoSettings() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			fixture.userSettings("""
				{"sandbox": {"enabled": true, "network": {"allowedDomains": ["api.example.com"]}}}""");
			fixture.localSettings("");
			ParentPolicy policy = fixture.policy();
			requireThat(policy.sandboxed(), "sandboxed").isTrue();
			requireThat(policy.hosts().allows("api.example.com"), "userHost").isTrue();
		}
	}

	/**
	 * Verifies that a proxy port, which sends the commands' traffic through a proxy that CAT cannot read, is refused.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void proxyPortIsRefused() throws IOException
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			fixture.userSettings("""
				{"sandbox": {"enabled": true, "network": {"httpProxyPort": 8888}}}""");
			requireThat(fixture.refusal(), "refusal").contains("httpProxyPort");
		}
	}

	/**
	 * Verifies that a hook environment without {@code CLAUDE_PID} is refused.
	 */
	@Test
	public void missingProcessIdIsRefused()
	{
		try
		{
			ClaudeSession session = ClaudeSession.fromHookEnvironment(Map.of("CLAUDE_PROJECT_DIR", "/work"),
				Path.of("/home/user"), "user");
			throw new AssertionError("The session was not refused: " + session);
		}
		catch (PolicyRefusal e)
		{
			requireThat(e.messageFor(ClaudePolicyReader.HARNESS_NAME), "refusal").startsWith(REFUSAL).
				contains("CLAUDE_PID");
		}
	}

	/**
	 * Verifies that a process that is not running, or that started at another instant than the session's process,
	 * is refused.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the first reading is refused
	 */
	@Test
	public void otherProcessIsRefused() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			fixture.userSettings(ENABLED);
			ClaudePolicyReading snapshot = fixture.read();
			fixture.reportedStart = Instant.parse("2026-11-05T13:00:00Z");
			try
			{
				ParentPolicy policy = fixture.reader().reread(fixture.session(), snapshot);
				throw new AssertionError("The reread was not refused: " + policy);
			}
			catch (PolicyRefusal e)
			{
				requireThat(e.getMessage(), "refusal").contains("another process");
			}
			fixture.running = false;
			requireThat(fixture.refusal(), "refusal").contains("not running");
		}
	}

	/**
	 * Verifies that a project settings file that appears after CAT first read the policy narrows the policy that a
	 * later reading gives, but does not widen it.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void laterProjectFileOnlyNarrows() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			fixture.userSettings("""
				{"sandbox": {"enabled": true, "network": {"allowedDomains": ["api.example.com"]}}}""");
			ClaudePolicyReading snapshot = fixture.read();
			Path widened = fixture.root.resolve("widened");
			Path denied = fixture.project.resolve("denied");
			fixture.projectSettings("""
				{"sandbox": {"filesystem": {"allowWrite": ["%s"], "denyWrite": ["%s"], "denyRead": ["%s"]},
				  "network": {"allowedDomains": ["new.example.com"], "allowLocalBinding": true}}}""".formatted(
				widened, denied, denied));

			ParentPolicy policy = fixture.reader().reread(fixture.session(), snapshot);
			requireThat(policy.sandboxed(), "sandboxed").isTrue();
			requireThat(policy.writeRoots(), "writeRoots").doesNotContain(widened);
			requireThat(policy.writeDenials(), "writeDenials").contains(denied);
			requireThat(policy.readDenials(), "readDenials").contains(denied);
			requireThat(policy.hosts().allows("api.example.com"), "snapshotHost").isTrue();
			requireThat(policy.hosts().allows("new.example.com"), "newHost").isFalse();
			requireThat(policy.allowLocalBinding(), "allowLocalBinding").isFalse();
		}
	}

	/**
	 * Verifies that a managed source whose precedence CAT does not know contributes only the entries that narrow
	 * the policy, and that managed settings in a form that CAT cannot read are refused.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void uncertainManagedSourceNarrows() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			fixture.userSettings(ENABLED);
			Path widened = fixture.root.resolve("widened");
			Path denied = fixture.project.resolve("denied");
			write(fixture.configuration.resolve("remote-settings.json"), """
				{"sandbox": {"filesystem": {"allowWrite": ["%s"], "denyWrite": ["%s"]}}}""".formatted(widened,
				denied));
			ParentPolicy policy = fixture.policy();
			requireThat(policy.writeRoots(), "writeRoots").doesNotContain(widened);
			requireThat(policy.writeDenials(), "writeDenials").contains(denied);

			write(fixture.unreadableManaged, "<plist/>");
			requireThat(fixture.refusal(), "refusal").contains(fixture.unreadableManaged.toString());
		}
	}

	/**
	 * Verifies that the settings files that CAT reads are protected sources of the policy, and that a settings file
	 * that the session's commands may change, because the settings lift the sandbox's file restrictions, is
	 * refused.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void writableSettingsFileIsRefused() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			fixture.userSettings(ENABLED);
			requireThat(fixture.policy().protectedSources(), "protectedSources").containsAll(List.of(
				fixture.project.resolve(".claude/settings.json"),
				fixture.project.resolve(".claude/settings.local.json"),
				fixture.configuration.resolve("settings.json")));

			Path flagFile = write(fixture.project.resolve("flags/settings.json"), "{}");
			fixture.arguments.addAll(List.of("--settings", flagFile.toString()));
			requireThat(fixture.policy().protectedSources(), "protectedSources").contains(flagFile);

			fixture.arguments.clear();
			fixture.userSettings("""
				{"sandbox": {"enabled": true, "filesystem": {"disabled": true}}}""");
			requireThat(fixture.refusal(), "refusal").startsWith(REFUSAL).
				contains(fixture.configuration.resolve("settings.json").toString());
		}
	}
}
