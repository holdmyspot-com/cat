package com.holdmyspot.cat.claude.policy.test;

import com.holdmyspot.cat.claude.policy.ClaudePolicyReader;
import com.holdmyspot.cat.claude.policy.ClaudeSession;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static com.holdmyspot.cat.claude.policy.test.ClaudeSessionFixture.write;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that CAT reads a Claude Code session's sandbox policy from its settings as Claude Code applies them, finds the
 * session's project from Claude Code's record of the session, and refuses each source that it cannot read safely.
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
	 * The ID of the session's Claude Code process.
	 */
	private static final long PROCESS_ID = 4242;
	/**
	 * The session's identifier.
	 */
	private static final String SESSION_ID = "5d2c4a8e-1f3b-4c6d-9e7f-8a9b0c1d2e3f";

	/**
	 * Creates the test.
	 */
	public ClaudePolicyReaderTest()
	{
	}

	/**
	 * Skips the tests on native Windows, where Claude Code has no sandbox, so that no Claude Code session's sandbox
	 * policy is read there.
	 */
	@BeforeClass
	public void requireSandboxPlatform()
	{
		if (Platform.current().isWindows())
		{
			throw new SkipException("Claude Code has no sandbox on native Windows, so CAT reads no session's " +
				"policy there");
		}
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
	 * highest: managed settings, whose drop-in files override the managed settings file and whose later drop-in files
	 * override earlier ones, then local, project and user settings.
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
			write(fixture.managed.resolve("managed-settings.json"), noBind);
			requireThat(fixture.policy().allowLocalBinding(), "managed").isFalse();
			write(fixture.managed.resolve("managed-settings.d/20-late.json"), bind);
			write(fixture.managed.resolve("managed-settings.d/10-early.json"), noBind);
			requireThat(fixture.policy().allowLocalBinding(), "dropIn").isTrue();
		}
	}

	/**
	 * Verifies that the sandbox's lists merge across every layer: managed settings and their drop-in files, and the
	 * local, project and user settings.
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
			write(fixture.managed.resolve("managed-settings.json"), """
				{"sandbox": {"filesystem": {"allowWrite": ["%s"]},
				  "network": {"allowedDomains": ["*.managed.example.com"]}}}""".formatted(root.resolve("w-managed")));
			write(fixture.managed.resolve("managed-settings.d/10-drop-in.json"),
				allowWrite(root.resolve("w-drop-in")));

			ParentPolicy policy = fixture.policy();
			requireThat(policy.writeRoots(), "writeRoots").containsAll(List.of(fixture.project,
				root.resolve("w-user"), root.resolve("w-project"), root.resolve("w-local"),
				root.resolve("w-managed"), root.resolve("w-drop-in")));
			requireThat(policy.hosts().allows("user.example.com"), "userHost").isTrue();
			requireThat(policy.hosts().allows("api.managed.example.com"), "managedHost").isTrue();
			requireThat(policy.hosts().allows("other.example.com"), "otherHost").isFalse();
		}
	}

	/**
	 * Verifies that {@code Read(...)} deny rules of the settings deny reads, in each spelling of a rule's path.
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

			List<Path> readDenials = fixture.policy().readDenials();
			requireThat(readDenials, "readDenials").containsAll(List.of(fixture.root.resolve("sandbox-denied"),
				fixture.root.resolve("absolute"), fixture.home.resolve("keys"), secret));
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
	 * Verifies that a command environment without {@code CLAUDE_PID} is refused.
	 */
	@Test
	public void missingProcessIdIsRefused()
	{
		try
		{
			ClaudeSession session = ClaudeSession.fromCommandEnvironment(Map.of("CLAUDECODE", "1",
				"CLAUDE_CODE_SESSION_ID", SESSION_ID), Path.of("/home/user"), "user");
			throw new AssertionError("The session was not refused: " + session);
		}
		catch (IOException | PolicyRefusal e)
		{
			requireThat(e, "e").isInstanceOf(PolicyRefusal.class);
			requireThat(((PolicyRefusal) e).messageFor(ClaudePolicyReader.HARNESS_NAME), "refusal").
				startsWith(REFUSAL).contains("CLAUDE_PID");
		}
	}

	/**
	 * Verifies that the session that runs a command is the one that the command environment names, and that its
	 * project is the folder that Claude Code's record of the session names, whichever folder the command runs in.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the session is refused
	 */
	@Test
	public void projectComesFromSessionRecord() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			fixture.sessionRecord(PROCESS_ID, SESSION_ID, fixture.project);

			ClaudeSession session = ClaudeSession.fromCommandEnvironment(environment(), fixture.home, "user");

			requireThat(session.projectDirectory(), "projectDirectory").isEqualTo(fixture.project);
			requireThat(session.configurationDirectory(), "configurationDirectory").
				isEqualTo(fixture.configuration);
		}
	}

	/**
	 * Verifies that a record that Claude Code keeps under the process ID that the command environment names, but
	 * that belongs to another session, is refused rather than read, so that a process ID that another process reuses
	 * never makes CAT read another session's settings.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void recordOfAnotherSessionIsRefused() throws IOException
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			fixture.sessionRecord(PROCESS_ID, "0b6f7c1e-2d3a-4e5f-8a9b-0c1d2e3f4a5b", fixture.project);
			try
			{
				ClaudeSession session = ClaudeSession.fromCommandEnvironment(environment(), fixture.home, "user");
				throw new AssertionError("The session was not refused: " + session);
			}
			catch (PolicyRefusal e)
			{
				requireThat(e.messageFor(ClaudePolicyReader.HARNESS_NAME), "refusal").
					contains("belongs to another session").contains(SESSION_ID);
			}
		}
	}

	/**
	 * Returns the environment of a command that the fixture's Claude Code session runs in its sandbox.
	 *
	 * @return the environment
	 */
	private static Map<String, String> environment()
	{
		return Map.of("CLAUDECODE", "1", "SANDBOX_RUNTIME", "1", "CLAUDE_PID", Long.toString(PROCESS_ID),
			"CLAUDE_CODE_SESSION_ID", SESSION_ID);
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
	 * Verifies that settings that lift the sandbox's file restrictions let the session's commands write every folder.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the policy is refused
	 */
	@Test
	public void liftedFileRestrictionsWriteEverywhere() throws IOException, PolicyRefusal
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			fixture.userSettings("""
				{"sandbox": {"enabled": true, "filesystem": {"disabled": true}}}""");
			ParentPolicy policy = fixture.policy();
			requireThat(policy.sandboxed(), "sandboxed").isTrue();
			requireThat(policy.writeRoots(), "writeRoots").isEqualTo(List.of(Path.of("/")));
		}
	}
}
