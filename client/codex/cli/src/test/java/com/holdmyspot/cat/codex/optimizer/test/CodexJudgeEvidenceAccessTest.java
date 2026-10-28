package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.optimizer.CodexSessions;
import com.holdmyspot.cat.codex.runner.CodexConnectionConfiguration;
import com.holdmyspot.cat.codex.runner.CodexRunner;
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
import java.util.List;
import java.util.Map;
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
 * denied.
 * <p>
 * The bridge stands in for the Codex runner up to the Codex process: it parses the runner options that
 * {@link CodexSessions} sends, as the runner does, and writes the permission profile that the runner gives the
 * session's thread. The scripted harness then plays the judge's reply in place of Codex.
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
	 * Verifies that the permission profile of a Codex judge, started for a development trial inside the host project,
	 * lets the judge read the trial's project and artifacts but write neither, and denies it a sibling trial's run
	 * and the host project's other files.
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
			Path effects = trial.directory().resolve("project");
			Path trialArtifacts = trial.directory().resolve("artifacts");

			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, artifact, runRoot, List::of, "", false,
				Map.of(), hostProject, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer(),
				Optional.empty(), hostProject);
			List<Path> configurations = new CopyOnWriteArrayList<>();
			List<String> profiles = new CopyOnWriteArrayList<>();
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
				profiles.add(CodexRunner.addSessionProfile(configuration, judges, judge, options.readOnlyEvidence(),
					options.deniedReads(), platform, Map.of(), null));
				configurations.add(configuration);
				ScriptedHarness.serve(options.runRoot(), "codex", List.of(Turn.replies("{}")), input, output);
			});

			SessionResult result = sessions.start(new SessionRequest(SessionRole.JUDGE, Part.HELD_OUT, judgeSource,
				List.of("judge"), List.of(effects, trialArtifacts), LIMIT, TrialProfile.HARNESS_DEFAULT)).
				completion().toCompletableFuture().get(60, TimeUnit.SECONDS);

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			requireThat(configurations.size(), "configurations.size()").isEqualTo(1);
			JsonNode permissions = TomlMapper.builder().build().readTree(Files.readString(configurations.getFirst())).
				path("permissions");
			String profile = profiles.getFirst();
			for (Path evidence : List.of(effects, trialArtifacts))
			{
				requireThat(access(permissions, profile, evidence), "access(" + evidence + ")").isEqualTo("read");
			}
			requireThat(access(permissions, profile, sibling.directory()), "siblingAccess").isEqualTo("deny");
			requireThat(access(permissions, profile, instructions), "instructionsAccess").isEqualTo("deny");
		}
	}

	/**
	 * Returns the access that a thread's permission profile, with the profiles that it extends, gives to an existing
	 * path: that of the deepest entry that covers the path, as Codex's sandbox resolves overlapping entries.
	 *
	 * @param permissions the {@code permissions} table of the session's configuration
	 * @param profile     the profile that the thread names
	 * @param path        an existing path outside the thread's workspace
	 * @return {@code read}, {@code write} or {@code deny}, or {@code none} if no entry covers the path
	 * @throws IOException if the real path of {@code path} cannot be read
	 */
	private static String access(JsonNode permissions, String profile, Path path) throws IOException
	{
		Path real = path.toRealPath();
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
