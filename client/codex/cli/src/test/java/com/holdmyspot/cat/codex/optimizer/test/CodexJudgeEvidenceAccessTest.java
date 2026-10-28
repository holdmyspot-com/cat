package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.optimizer.CodexSessions;
import com.holdmyspot.cat.codex.runner.CodexConnectionConfiguration;
import com.holdmyspot.cat.codex.runner.CodexRunner;
import com.holdmyspot.cat.codex.runner.CodexRunner.SessionSandbox;
import com.holdmyspot.cat.codex.runner.CodexRunnerCli;
import com.holdmyspot.cat.codex.secrets.CodexSessionsSecrets;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.adapter.fakes.test.RoleRuleFixtures;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Tests that a Codex judge may read its trial's evidence, but not change it, while the rest of the host project stays
 * denied, however many runs the trial's run root holds.
 * <p>
 * The bridge stands in for the Codex runner up to the Codex process: it parses the runner options that
 * {@link CodexSessions} sends, as the runner does, and builds the sandbox that the runner gives the session: the
 * permission profile of its thread and the command that starts its app-server. The scripted harness then plays the
 * judge's reply in place of Codex.
 */
public final class CodexJudgeEvidenceAccessTest
{
	/**
	 * The longest that the judge session may run.
	 */
	private static final Duration LIMIT = Duration.ofMinutes(5);

	/**
	 * Creates the test.
	 */
	public CodexJudgeEvidenceAccessTest()
	{
	}

	/**
	 * Returns the platforms whose sandboxes deny paths in their own way.
	 *
	 * @return the platforms
	 */
	@DataProvider
	public Object[][] platforms()
	{
		return new Object[][]{{Platform.LINUX_X64}, {Platform.MACOS_ARM64}, {Platform.WINDOWS_X64}};
	}

	/**
	 * Verifies that the sandbox of a Codex judge, started for a development trial inside the host project, lets the
	 * judge read the trial's project and artifacts but write neither, denies it a sibling trial's run and the host
	 * project's other files, and leaves its own project and home writable to the app-server.
	 *
	 * @param platform the platform that the judge runs on
	 * @throws IOException          if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException   if the session cannot determine a result
	 * @throws TimeoutException     if the session does not finish
	 */
	@Test(dataProvider = "platforms", timeOut = 120_000)
	public void judgeReadsOnlyItsTrialsEvidence(Platform platform)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-P2
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			JudgeSandbox sandbox = startJudge(jvm, cleanup, platform, 0);

			for (Path evidence : sandbox.evidence())
				requireThat(sandbox.access(evidence), "access(" + evidence + ")").isEqualTo("read");
			requireThat(sandbox.access(sandbox.sibling()), "siblingAccess").isEqualTo("deny");
			requireThat(sandbox.access(sandbox.instructions()), "instructionsAccess").isEqualTo("deny");
			for (String own : List.of("project", "home"))
			{
				Path path = sandbox.judgeRun().resolve(own);
				requireThat(sandbox.mountedWritable(path), "mountedWritable(" + path + ")").isTrue();
			}
		}
	}

	/**
	 * Verifies that the sandbox of a Codex judge whose trial ran beside hundreds of other trials stays small enough
	 * for Codex to pass it to its sandbox helper, which receives the whole permission profile as one argument and
	 * fails to start the judge's thread once that argument reaches Linux's limit of 128 KiB. The permission profile
	 * and the app-server command each stay under 16 KiB, which no number of runs changes, and the judge still reads
	 * the trial's evidence but no sibling trial's run.
	 *
	 * @param platform the platform that the judge runs on
	 * @throws IOException          if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException   if the session cannot determine a result
	 * @throws TimeoutException     if the session does not finish
	 */
	@Test(dataProvider = "platforms", timeOut = 120_000)
	public void judgeSandboxStaysBoundedBesideManyRuns(Platform platform)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			JudgeSandbox sandbox = startJudge(jvm, cleanup, platform, 200);

			int bound = 16 * 1024;
			requireThat(sandbox.profileBytes(), "profileBytes").isLessThan(bound);
			requireThat(String.join(" ", sandbox.command()).length(), "commandLength").isLessThan(bound);
			for (Path evidence : sandbox.evidence())
				requireThat(sandbox.access(evidence), "access(" + evidence + ")").isEqualTo("read");
			requireThat(sandbox.access(sandbox.sibling()), "siblingAccess").isEqualTo("deny");
			requireThat(sandbox.access(sandbox.instructions()), "instructionsAccess").isEqualTo("deny");
			for (String own : List.of("project", "home"))
			{
				Path path = sandbox.judgeRun().resolve(own);
				requireThat(sandbox.mountedWritable(path), "mountedWritable(" + path + ")").isTrue();
			}
		}
	}

	/**
	 * Starts a judge session for a development trial inside a host project, through a bridge that builds the judge's
	 * sandbox as the Codex runner does, with bubblewrap on the {@code PATH}. The host project lies in the JVM's
	 * temporary directory, which the sandbox makes private to the app-server as it does {@code /tmp}, so that the judge
	 * meets the host project under its private temporary directory, as on a host whose temporary directory is
	 * {@code /tmp}.
	 *
	 * @param jvm      the JVM scope
	 * @param cleanup  deletes the fixture's directories
	 * @param platform the platform that the judge runs on
	 * @param runs     how many other runs the trial's run root holds besides the trial and one sibling
	 * @return the judge's sandbox
	 * @throws IOException          if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException   if the session cannot determine a result
	 * @throws TimeoutException     if the session does not finish
	 */
	private static JudgeSandbox startJudge(TestJvmScope jvm, TemporaryDirectories cleanup, Platform platform,
		int runs) throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		Path hostProject = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-host-project-")).
			toRealPath();
		Path instructions = Files.writeString(hostProject.resolve("AGENTS.md"), "Follow the handbook.");
		Path artifact = RoleRuleFixtures.write(hostProject.resolve("artifact"));
		Path judgeSource = Files.createDirectories(hostProject.resolve("judge-source"));
		Path runRoot = hostProject.resolve(".cat/sessions/session-1/optimizer-runs/codex");
		HarnessRunManager trials = new HarnessRunManager(jvm.fileLocks(), runRoot, Clock.systemUTC(),
			Yielding.fileReplacer());
		HarnessRun trial = trials.create("codex");
		HarnessRun sibling = trials.create("codex");
		for (int i = 0; i < runs; ++i)
			Files.createDirectories(runRoot.resolve("other-run-" + i));
		Path effects = trial.directory().resolve("project");
		Path trialArtifacts = trial.directory().resolve("artifacts");
		Path bin = Files.createDirectories(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
			"cat-bin-")));
		Path bwrap = Files.writeString(bin.resolve("bwrap"), "");
		requireThat(bwrap.toFile().setExecutable(true), "setExecutable").isTrue();

		RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, artifact, runRoot, List::of, "", false,
			Map.of(), hostProject, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer(),
			Optional.empty(), hostProject);
		List<Path> configurations = new CopyOnWriteArrayList<>();
		List<Path> judgeRuns = new CopyOnWriteArrayList<>();
		List<SessionSandbox> sandboxes = new CopyOnWriteArrayList<>();
		CodexSessions sessions = CodexSessionsSecrets.getCodexSessionsAccess().
			newCodexSessions(settings, (arguments, input, output) ->
		{
			HarnessRunnerOptions options = CodexRunnerCli.options(arguments.toArray(String[]::new),
				HarnessTemporaryRoot.withoutSession("The judge runs outside a harness session"));
			HarnessRunManager judges = new HarnessRunManager(jvm.fileLocks(), options.runRoot(), Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun judge = judges.create("codex");
			Path configuration = judge.directory().resolve("home/config.toml");
			CodexConnectionConfiguration.copy(judge.directory().resolve("missing.toml"), configuration,
				judge.directory().resolve("project"));
			sandboxes.add(CodexRunner.sessionSandbox(configuration, judges, judge, options.readOnlyEvidence(),
				options.deniedReads(), platform, Map.of("PATH", bin.toString()), null,
				CodexRunner.appServerCommand("codex", false), jvm.temporaryDirectory().toRealPath()));
			configurations.add(configuration);
			judgeRuns.add(judge.directory());
			ScriptedHarness.serve(options.runRoot(), "codex", List.of(Turn.replies("{}")), input, output);
		});

		SessionResult result = sessions.start(new SessionRequest(SessionRole.JUDGE, Part.HELD_OUT, judgeSource,
			List.of("judge"), List.of(effects, trialArtifacts), LIMIT, TrialProfile.HARNESS_DEFAULT)).
			completion().toCompletableFuture().get(60, TimeUnit.SECONDS);

		requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
		requireThat(configurations.size(), "configurations.size()").isEqualTo(1);
		String configuration = Files.readString(configurations.getFirst());
		JsonNode permissions = TomlMapper.builder().build().readTree(configuration).path("permissions");
		SessionSandbox sandbox = sandboxes.getFirst();
		return new JudgeSandbox(permissions, sandbox.profile(), profileBytes(configuration), sandbox.command(),
			List.of(effects, trialArtifacts), sibling.directory(), instructions, judgeRuns.getFirst());
	}

	/**
	 * Returns the size of the permission profiles in a session's configuration, from the first {@code permissions}
	 * table to the end, which Codex resolves into the policy that its sandbox helper receives.
	 *
	 * @param configuration the session's configuration
	 * @return the size in UTF-8 bytes
	 */
	private static int profileBytes(String configuration)
	{
		int start = configuration.indexOf("[permissions.");
		if (start < 0)
			return 0;
		return configuration.substring(start).getBytes(UTF_8).length;
	}

	/**
	 * The sandbox that a judge session received.
	 *
	 * @param permissions  the {@code permissions} table of the session's configuration
	 * @param profile      the profile that the thread names
	 * @param profileBytes the size of the configuration's permission profiles in UTF-8 bytes
	 * @param command      the command that starts the session's app-server
	 * @param evidence     the trial's evidence
	 * @param sibling      a sibling trial's run directory
	 * @param instructions a file of the host project outside every run
	 * @param judgeRun     the judge's own run directory
	 */
	private record JudgeSandbox(JsonNode permissions, String profile, int profileBytes, List<String> command,
		List<Path> evidence, Path sibling, Path instructions, Path judgeRun)
	{
		/**
		 * Indicates whether the app-server's bubblewrap mounts leave an existing path writable, so that the thread's
		 * permission profile alone decides whether the judge's commands may write it.
		 *
		 * @param path an existing path
		 * @return {@code true} unless the path is hidden or mounted read-only
		 * @throws IOException if the real path of {@code path} cannot be read
		 */
		boolean mountedWritable(Path path) throws IOException
		{
			String mount = mount(path.toRealPath());
			return !mount.equals("--tmpfs") && !mount.equals("--ro-bind");
		}

		/**
		 * Returns the access that the judge's commands have to an existing path outside the judge's workspace. The
		 * app-server command's bubblewrap mounts decide what the app-server sees: a path under a {@code --tmpfs} that
		 * no later bind reopens is hidden, and one under a {@code --ro-bind} is read-only. Inside that view, the
		 * thread's permission profile, with the profiles that it extends, gives the path the access of the deepest
		 * entry that covers it, as Codex's sandbox resolves overlapping entries.
		 *
		 * @param path an existing path outside the judge's workspace
		 * @return {@code read}, {@code write} or {@code deny}, or {@code none} if nothing restricts the path
		 * @throws IOException if the real path of {@code path} cannot be read
		 */
		String access(Path path) throws IOException
		{
			Path real = path.toRealPath();
			String mount = mount(real);
			if (mount.equals("--tmpfs"))
				return "deny";
			String result = profileAccess(real);
			if (mount.equals("--ro-bind") && !result.equals("deny"))
				return "read";
			return result;
		}

		/**
		 * Returns the bubblewrap mount through which the app-server sees a path: the deepest mount that covers it
		 * after each mount has replaced the earlier mounts at or below its destination.
		 *
		 * @param real the path's real path
		 * @return the mount's option, such as {@code --tmpfs} or {@code --ro-bind}, or {@code --dev-bind} if the
		 *   command runs no bubblewrap
		 */
		private String mount(Path real)
		{
			Map<Path, String> mounts = new LinkedHashMap<>();
			int separator = command.indexOf("--");
			if (separator < 0)
				return "--dev-bind";
			for (int i = 1; i < separator; ++i)
			{
				String option = command.get(i);
				Path destination;
				switch (option)
				{
					case "--tmpfs" ->
					{
						destination = Path.of(command.get(i + 1));
						i += 1;
					}
					case "--bind", "--ro-bind", "--dev-bind" ->
					{
						destination = Path.of(command.get(i + 2));
						i += 2;
					}
					default ->
					{
						continue;
					}
				}
				mounts.keySet().removeIf(earlier -> earlier.startsWith(destination));
				mounts.put(destination, option);
			}
			String result = "--dev-bind";
			int depth = -1;
			for (Entry<Path, String> entry : mounts.entrySet())
			{
				if (real.startsWith(entry.getKey()) && entry.getKey().getNameCount() > depth)
				{
					depth = entry.getKey().getNameCount();
					result = entry.getValue();
				}
			}
			return result;
		}

		/**
		 * Returns the access that the thread's permission profile, with the profiles that it extends, gives to a path:
		 * that of the deepest entry that covers the path.
		 *
		 * @param real the path's real path
		 * @return {@code read}, {@code write} or {@code deny}, or {@code none} if no entry covers the path
		 */
		private String profileAccess(Path real)
		{
			String result = "none";
			int depth = -1;
			String current = profile;
			while (permissions.has(current))
			{
				JsonNode filesystem = permissions.path(current).path("filesystem");
				for (String entry : filesystem.propertyNames())
				{
					// Codex's own placeholders, such as its temporary directory, are not paths
					if (entry.startsWith(":"))
						continue;
					Path covering = Path.of(entry);
					if (real.startsWith(covering) && covering.getNameCount() > depth)
					{
						depth = covering.getNameCount();
						result = filesystem.get(entry).stringValue();
					}
				}
				current = permissions.path(current).path("extends").stringValue("");
			}
			return result;
		}
	}
}
