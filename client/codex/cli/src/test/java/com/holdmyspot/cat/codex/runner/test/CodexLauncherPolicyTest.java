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
import org.testng.annotations.Test;

/**
 * Tests the sandbox policy that the Codex session launcher serves its session with: the policy that the command line
 * of the Codex process that the hook named and the session's configuration give.
 */
public final class CodexLauncherPolicyTest
{
	private static final long CODEX_PID = 5151;
	private static final Instant CODEX_START = Instant.parse("2026-11-05T12:00:00.456Z");

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

			ParentPolicy policy = source.read();

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
					ParentPolicy policy = source.read();
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
				"01a1a8eb-3d83-7d73-b243-c636c2041ee1", project, Optional.empty(), "startup", home.resolve(".codex"),
				PluginInfo.CAT_VERSION);
			CodexPolicyReader reader = new CodexPolicyReader(new CodexSystemFiles(system,
				List.of(system.resolve("managed.plist")), slashTmp));
			return CodexSessionLauncherCli.policySource(reader, processes, record, Map.of("HOME", home.toString()),
				home);
		}
	}
}
