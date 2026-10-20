package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
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
	 * The number of lines that a difference or a failure shows.
	 */
	private static final int SHOWN_LINES = 3;

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
			List<String> failures = new ArrayList<>();
			List<HookReport.HookDifference> differences = new ArrayList<>();
			int checked = 0;
			for (HookInvocation sessionHook : sessionHooks)
			{
				// A hook whose file trials do not receive does not run in trials
				Queue<HookInvocation> matching = trialHooks.get(sessionHook.name());
				if (matching == null || matching.isEmpty())
					continue;
				HookInvocation trialHook = matching.remove();
				++checked;
				Outcome session = execute(sessionHook, sessionProject, directory, "session-" + checked);
				Outcome trial = execute(trialHook, trialProject, directory, "trial-" + checked);
				if (session.succeeded() && !trial.succeeded())
				{
					failures.add(sessionHook.name() + " " + trial.describeFailure());
					continue;
				}
				List<String> sessionLines = session.output().lines().toList();
				List<String> trialLines = trial.output().lines().toList();
				int first = 0;
				while (first < sessionLines.size() && first < trialLines.size() &&
					sessionLines.get(first).equals(trialLines.get(first)))
				{
					++first;
				}
				if (first < sessionLines.size() || first < trialLines.size())
				{
					differences.add(new HookReport.HookDifference(sessionHook.name(),
						from(trialLines, first), from(sessionLines, first)));
				}
			}
			return new Result(failures, new HookReport(checked, differences));
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(directory);
		}
	}

	/**
	 * Returns the lines that a difference shows.
	 *
	 * @param lines the hook's output
	 * @param first the index of the first line that differs
	 * @return at most {@link #SHOWN_LINES} lines from {@code first}
	 */
	private static List<String> from(List<String> lines, int first)
	{
		if (first >= lines.size())
			return List.of();
		return lines.subList(first, Math.min(lines.size(), first + SHOWN_LINES));
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
			new String(Files.readAllBytes(error), StandardCharsets.UTF_8), hook.timeout().toSeconds());
	}

	/**
	 * How a hook ended.
	 *
	 * @param exitStatus the hook's exit status, or empty if it did not end before its timeout
	 * @param output     the hook's standard output
	 * @param error      the hook's error output
	 * @param timeout    the hook's timeout, in seconds
	 */
	private record Outcome(Optional<Integer> exitStatus, String output, String error, long timeout)
	{
		/**
		 * Returns whether the hook succeeded.
		 *
		 * @return {@code true} if it exited with status 0
		 */
		boolean succeeded()
		{
			return exitStatus.isPresent() && exitStatus.get() == 0;
		}

		/**
		 * Describes how the hook failed, with the end of its error output.
		 *
		 * @return the end of a sentence that starts with the hook's name
		 */
		String describeFailure()
		{
			String failure;
			if (exitStatus.isEmpty())
				failure = "does not end within " + timeout + " seconds in a trial's workspace";
			else
				failure = "exits with status " + exitStatus.get() + " in a trial's workspace";
			List<String> lines = error.strip().lines().toList();
			if (lines.isEmpty())
				return failure + ", with no error output";
			List<String> end = lines.subList(Math.max(0, lines.size() - SHOWN_LINES), lines.size());
			return failure + ", and its error output ends with: " + String.join(" / ", end);
		}
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
