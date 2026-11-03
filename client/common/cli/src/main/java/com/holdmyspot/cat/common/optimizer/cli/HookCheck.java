package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.hooks.HookComparison;
import com.holdmyspot.cat.common.optimizer.hooks.HookComparison.Outcome;
import com.holdmyspot.cat.common.optimizer.port.HookInvocation;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.proposal.HookReport;
import com.holdmyspot.cat.common.optimizer.workspace.FileTree;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * Runs the hooks that run before the model acts, at session start or prompt submission, without a model: once in a
 * copy of the staged base source with a new, empty harness home, as a trial runs them, and once in a copy of the
 * fork with the user's home, as the user's sessions run them. A hook's command does not show which files it reads,
 * so running it is the only way to find a hook that fails in trials.
 */
final class HookCheck
{
	/**
	 * The prompt that the hooks receive when the user submits a prompt.
	 */
	static final String PROMPT = "Describe this project.";

	/**
	 * Prevents instantiation.
	 */
	private HookCheck()
	{
	}

	/**
	 * Runs the hooks.
	 *
	 * @param sessions    lists the hooks as the harness runs them
	 * @param trialSource the staged base source that trials copy
	 * @param forkSource  a staged source that holds every file of the fork
	 * @param plugins     the campaign's trial plugins with their base copies
	 * @param scratch     a directory that receives the copies and the hooks' output, and is deleted afterwards
	 * @return the hooks that succeed in the user's sessions and fail in trials, each as a sentence, and the report
	 * @throws IOException          if a copy cannot be made, a hook cannot be started or its output cannot be read
	 * @throws InterruptedException if the thread is interrupted while it waits for a hook
	 */
	static Result run(SessionPort sessions, Path trialSource, Path forkSource, List<PluginBase> plugins,
		Path scratch) throws IOException, InterruptedException
	{
		requireThat(sessions, "sessions").isNotNull();
		requireThat(trialSource, "trialSource").isNotNull();
		requireThat(forkSource, "forkSource").isNotNull();
		requireThat(plugins, "plugins").isNotNull();
		requireThat(scratch, "scratch").isNotNull();
		Files.createDirectories(scratch);
		Path directory = Files.createTempDirectory(scratch, "hooks-");
		try
		{
			Path sessionProject = directory.resolve("session").resolve("project");
			FileTree.copy(forkSource, sessionProject, _ -> false);
			Path trialProject = directory.resolve("trial").resolve("project");
			FileTree.copy(trialSource, trialProject, _ -> false);
			Path trialHome = Files.createDirectories(directory.resolve("trial").resolve("home"));

			Map<String, Queue<HookInvocation>> trialHooks = new HashMap<>();
			for (HookInvocation hook : sessions.earlyHooks(trialProject, plugins, Optional.of(trialHome), PROMPT))
				trialHooks.computeIfAbsent(hook.name(), _ -> new ArrayDeque<>()).add(hook);
			List<HookInvocation> sessionHooks = sessions.earlyHooks(sessionProject, plugins, Optional.empty(),
				PROMPT);
			List<HookComparison.Run> runs = new ArrayList<>();
			for (HookInvocation sessionHook : sessionHooks)
			{
				// A hook whose file trials do not receive does not run in trials
				Queue<HookInvocation> matching = trialHooks.get(sessionHook.name());
				if (matching == null || matching.isEmpty())
					continue;
				HookInvocation trialHook = matching.remove();
				int number = runs.size() + 1;
				Outcome session = execute(sessionHook, sessionProject, directory, "session-" + number);
				Outcome trial = execute(trialHook, trialProject, directory, "trial-" + number);
				runs.add(new HookComparison.Run(sessionHook.name(), session, trial));
			}
			HookComparison.Verdict verdict = HookComparison.compare(runs);
			return new Result(verdict.failures(), verdict.report());
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(directory);
		}
	}

	/**
	 * Runs one hook as the harness runs it.
	 *
	 * @param hook      the hook
	 * @param project   the directory that the hook runs in
	 * @param directory the directory that receives the hook's input and output
	 * @param name      the name of the hook's files in {@code directory}
	 * @return how the hook ended
	 * @throws IOException          if the hook cannot be started or its output cannot be read
	 * @throws InterruptedException if the thread is interrupted while it waits for the hook
	 */
	private static Outcome execute(HookInvocation hook, Path project, Path directory, String name)
		throws IOException, InterruptedException
	{
		Path input = directory.resolve(name + ".in");
		Path output = directory.resolve(name + ".out");
		Path error = directory.resolve(name + ".err");
		Files.writeString(input, hook.input(), StandardCharsets.UTF_8);
		ProcessBuilder builder = new ProcessBuilder(hook.command()).directory(project.toFile()).
			redirectInput(input.toFile()).redirectOutput(output.toFile()).redirectError(error.toFile());
		Map<String, String> environment = builder.environment();
		environment.putAll(hook.environment());
		// The copies lie inside the user's repository, which the hook must not find in place of the project's
		HarnessEnvironment.preventGitDiscoveryAbove(environment, project.getParent());
		Optional<Integer> exitStatus;
		try (Process process = builder.start())
		{
			if (process.waitFor(hook.timeout().toMillis(), TimeUnit.MILLISECONDS))
				exitStatus = Optional.of(process.exitValue());
			else
			{
				process.descendants().forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
				process.waitFor();
				exitStatus = Optional.empty();
			}
		}
		return new Outcome(exitStatus, new String(Files.readAllBytes(output), StandardCharsets.UTF_8),
			new String(Files.readAllBytes(error), StandardCharsets.UTF_8), hook.timeout());
	}

	/**
	 * The result of running the hooks.
	 *
	 * @param failures each hook that succeeds in the user's sessions and fails in trials, as a sentence fragment
	 * @param report   the number of hooks that ran and those whose output differs
	 */
	record Result(List<String> failures, HookReport report)
	{
		/**
		 * Creates a result.
		 *
		 * @param failures each hook that succeeds in the user's sessions and fails in trials, as a sentence fragment
		 * @param report   the number of hooks that ran and those whose output differs
		 * @throws NullPointerException if any of the arguments are null
		 */
		Result
		{
			requireThat(failures, "failures").isNotNull();
			requireThat(report, "report").isNotNull();
			failures = List.copyOf(failures);
		}
	}
}
