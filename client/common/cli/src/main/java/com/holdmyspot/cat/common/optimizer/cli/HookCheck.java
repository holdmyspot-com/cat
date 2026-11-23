package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.cli.AbstractOptimizerCommandScope.HookPairResolver;
import com.holdmyspot.cat.common.optimizer.hooks.HookComparison;
import com.holdmyspot.cat.common.optimizer.hooks.HookComparison.Outcome;
import com.holdmyspot.cat.common.optimizer.port.HookInspection;
import com.holdmyspot.cat.common.optimizer.port.HookInvocation;
import com.holdmyspot.cat.common.optimizer.port.HookPair;
import com.holdmyspot.cat.common.optimizer.proposal.HookReport;
import com.holdmyspot.cat.common.optimizer.workspace.FileTree;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HookComparisonRequest;
import com.holdmyspot.cat.common.runner.HookComparisonResult;
import com.holdmyspot.cat.common.runner.TrialPluginInput;

import java.io.IOException;
import java.io.OutputStream;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Runs the hooks that run before the model acts, at session start or prompt submission, without a model: once in a
 * copy of the staged base source with a new, empty harness home, as a trial runs them, and once in a copy of the
 * fork with the user's home, as the user's sessions run them. A hook's command does not show which files it reads,
 * so running it is the only way to find a hook that fails in trials.
 */
public final class HookCheck
{
	/**
	 * The prompt that the hooks receive when the user submits a prompt.
	 */
	public static final String PROMPT = "Describe this project.";
	private static final int MAXIMUM_OUTPUT_BYTES = 1024 * 1024;

	/**
	 * Prevents instantiation.
	 */
	private HookCheck()
	{
	}

	/**
	 * Runs the hooks.
	 *
	 * @param hooks       selects and pairs hooks for the copied workspaces before either invocation runs
	 * @param launcher    starts a hook within the owning session's permissions
	 * @param trialSource the staged base source that trials copy
	 * @param forkSource  a staged source that holds every file of the fork
	 * @param plugins     the staged plugin inputs for the trial
	 * @param scratch     a directory that receives the copies and the hooks' output, and is deleted afterwards
	 * @return the hooks that succeed in the user's sessions and fail in trials, each as a sentence, and the report
	 * @throws IOException          if a copy cannot be made, a hook cannot be started or its output cannot be read
	 * @throws InterruptedException if the thread is interrupted while it waits for a hook
	 */
	static Result run(HookPairResolver hooks, Launcher launcher, Path trialSource, Path forkSource,
		List<TrialPluginInput> plugins, Path scratch) throws IOException, InterruptedException
	{
		requireThat(hooks, "hooks").isNotNull();
		requireThat(launcher, "launcher").isNotNull();
		return withCopies(trialSource, forkSource, plugins, scratch, request ->
		{
			Path trialHome = Files.createDirectories(request.trial().resolveSibling("home"));
			HookInspection inspection = hooks.read(request.production(), request.trial(), trialHome, request.plugins());
			return comparePrepared(inspection, launcher, request.production(), request.trial(), trialHome,
				request.workspace());
		});
	}

	/**
	 * Sends prepared project copies to the peer evaluator through the owning session.
	 *
	 * @param comparator the authenticated peer comparison
	 * @param trialSource the staged source for trial workspaces
	 * @param forkSource the full project staged for production
	 * @param plugins the staged plugin inputs for the trial
	 * @param scratch the parent of the temporary workspace copies
	 * @return the observations returned by the evaluator
	 * @throws IOException if the workspace copies or the peer comparison fail
	 * @throws InterruptedException if the comparison is interrupted
	 */
	static Result runPeer(PeerComparator comparator, Path trialSource, Path forkSource, List<TrialPluginInput> plugins,
		Path scratch) throws IOException, InterruptedException
	{
		return withCopies(trialSource, forkSource, plugins, scratch, request ->
		{
			HookComparisonResult result = comparator.compare(request);
			return new Result(result.failures(), result.report(), Optional.of(result.unapproved()));
		});
	}

	/**
	 * Copies the workspaces and removes them once the comparison has returned.
	 *
	 * @param trialSource the staged source for trial workspaces
	 * @param forkSource the full project staged for production
	 * @param plugins the staged plugin inputs for the trial
	 * @param scratch the parent of the temporary workspace copies
	 * @param comparison the comparison that consumes the copies
	 * @return the observations
	 * @throws IOException if preparing the copies or running the comparison fails
	 * @throws InterruptedException if the comparison is interrupted
	 */
	private static Result withCopies(Path trialSource, Path forkSource, List<TrialPluginInput> plugins, Path scratch,
		PreparedComparison comparison) throws IOException, InterruptedException
	{
		requireThat(trialSource, "trialSource").isNotNull();
		requireThat(forkSource, "forkSource").isNotNull();
		requireThat(scratch, "scratch").isNotNull();
		Files.createDirectories(scratch);
		Path directory = Files.createTempDirectory(scratch, "hooks-");
		try
		{
			HookComparisonRequest request = new HookComparisonRequest(directory, plugins);
			FileTree.copy(forkSource, request.production(), _ -> false);
			FileTree.copy(trialSource, request.trial(), _ -> false);
			return comparison.compare(request);
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(directory);
		}
	}

	/**
	 * Runs hooks already paired by the evaluator against two prepared workspace copies.
	 *
	 * @param inspection the paired invocations and any unapproved hooks observed by the evaluator
	 * @param launcher starts each invocation within the owner's tool policy
	 * @param production the prepared production workspace
	 * @param trial the prepared trial workspace
	 * @param trialHome the fresh trial home
	 * @param directory the comparison directory holding the workspaces and home
	 * @return the comparison observations
	 * @throws IOException if a hook cannot be started or its output cannot be read
	 * @throws InterruptedException if the comparison is interrupted
	 */
	public static Result comparePrepared(HookInspection inspection, Launcher launcher, Path production, Path trial,
		Path trialHome, Path directory) throws IOException, InterruptedException
	{
		List<HookComparison.Run> runs = new ArrayList<>();
		for (HookPair pair : inspection.pairs())
		{
			Outcome session = execute(launcher, pair.production(), production, directory, Optional.empty());
			Outcome isolated = execute(launcher, pair.trial(), trial, directory, Optional.of(trialHome));
			runs.add(new HookComparison.Run(pair.production().name(), session, isolated));
		}
		HookComparison.Verdict verdict = HookComparison.compare(runs);
		return new Result(verdict.failures(), verdict.report(), inspection.unapproved());
	}

	/**
	 * Matches declarations for an evaluator whose hooks run without separate native approvals.
	 *
	 * @param hooks the evaluator's declaration reader
	 * @return the source that pairs matching declarations in their original order
	 */
	static HookPairResolver matchingDeclarations(InvocationSource hooks)
	{
		return (sessionProject, trialProject, trialHome, _) ->
		{
			Map<String, Queue<HookInvocation>> trialHooks = new HashMap<>();
			for (HookInvocation hook : hooks.read(trialProject, Optional.of(trialHome)))
				trialHooks.computeIfAbsent(hook.name(), _ -> new ArrayDeque<>()).add(hook);
			List<HookPair> pairs = new ArrayList<>();
			for (HookInvocation production : hooks.read(sessionProject, Optional.empty()))
			{
				Queue<HookInvocation> matching = trialHooks.get(production.name());
				if (matching != null && !matching.isEmpty())
					pairs.add(new HookPair(production, matching.remove()));
			}
			return new HookInspection(pairs, Optional.empty());
		};
	}

	/**
	 * Runs one hook as the harness runs it.
	 *
	 * @param launcher  starts the confined hook
	 * @param hook      the hook
	 * @param project   the directory that the hook runs in
	 * @param directory the comparison directory
	 * @param trialHome the isolated trial home, or empty for a production comparison
	 * @return how the hook ended
	 * @throws IOException          if the hook cannot be started or its output cannot be read
	 * @throws InterruptedException if the thread is interrupted while it waits for the hook
	 */
	private static Outcome execute(Launcher launcher, HookInvocation hook, Path project, Path directory,
		Optional<Path> trialHome)
		throws IOException, InterruptedException
	{
		ProcessBuilder builder = new ProcessBuilder(hook.command()).directory(project.toFile());
		Map<String, String> environment = builder.environment();
		if (trialHome.isPresent())
		{
			Map<String, String> scrubbed = HarnessEnvironment.scrub(environment);
			environment.clear();
			environment.putAll(scrubbed);
		}
		environment.putAll(hook.environment());
		// The copies lie inside the user's repository, which the hook must not find in place of the project's
		HarnessEnvironment.preventGitDiscoveryAbove(environment, project.getParent());
		try (Process process = launcher.start(builder, directory, trialHome))
		{
			FutureTask<String> output = new FutureTask<>(() -> BoundedInput.readUtf8(process.getInputStream(),
				hook.name(), MAXIMUM_OUTPUT_BYTES, "Startup-hook output"));
			FutureTask<String> error = new FutureTask<>(() -> BoundedInput.readUtf8(process.getErrorStream(),
				hook.name(), MAXIMUM_OUTPUT_BYTES, "Startup-hook error output"));
			Thread.startVirtualThread(output);
			Thread.startVirtualThread(error);
			Thread input = Thread.startVirtualThread(() -> sendInput(process, hook.input()));
			try
			{
				Optional<Integer> exitStatus = Optional.empty();
				if (process.waitFor(hook.timeout().toMillis(), TimeUnit.MILLISECONDS))
					exitStatus = Optional.of(process.exitValue());
				else
				{
					stop(process);
					process.waitFor();
				}
				return new Outcome(exitStatus, output.get(), error.get(), hook.timeout());
			}
			catch (ExecutionException e)
			{
				throw new IOException("Could not capture " + hook.name(), e.getCause());
			}
			finally
			{
				if (process.isAlive())
					stop(process);
				input.interrupt();
				output.cancel(true);
				error.cancel(true);
			}
		}
	}

	/**
	 * Sends the hook event and closes the pipe. A hook may ignore the event and exit before reading it.
	 *
	 * @param process the running hook
	 * @param input the event's JSON
	 */
	private static void sendInput(Process process, String input)
	{
		try (OutputStream stream = process.getOutputStream())
		{
			stream.write(input.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException _)
		{
			// A process that reads no input still has a valid exit status and output.
		}
	}

	/**
	 * Ends a timed-out or interrupted hook and its descendants.
	 *
	 * @param process the hook process
	 */
	private static void stop(Process process)
	{
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
	}

	/**
	 * Obtains an evaluator's declarations without executing a hook.
	 */
	@FunctionalInterface
	interface InvocationSource
	{
		/**
		 * Returns the commands the evaluator declares for the fixed comparison prompt.
		 *
		 * @param project the copied workspace
		 * @param trialHome the fresh trial home, or empty for a production comparison
		 * @return the evaluator's hook invocations
		 * @throws IOException if the evaluator cannot inspect its configuration
		 * @throws InterruptedException if the inspection is interrupted
		 */
		List<HookInvocation> read(Path project, Optional<Path> trialHome) throws IOException, InterruptedException;
	}

	/**
	 * Compares two prepared workspace copies.
	 */
	@FunctionalInterface
	private interface PreparedComparison
	{
		/**
		 * Compares hooks in the supplied copies.
		 *
		 * @param request the copies and staged trial plugins
		 * @return the comparison observations
		 * @throws IOException if the comparison fails
		 * @throws InterruptedException if the comparison is interrupted
		 */
		Result compare(HookComparisonRequest request) throws IOException, InterruptedException;
	}

	/**
	 * Invokes the peer evaluator through the owner's request server.
	 */
	@FunctionalInterface
	interface PeerComparator
	{
		/**
		 * Requests a comparison of hooks in the supplied copies.
		 *
		 * @param request the copies and staged trial plugins
		 * @return the peer's observations
		 * @throws IOException if the request or response fails
		 */
		HookComparisonResult compare(HookComparisonRequest request) throws IOException;
	}

	/**
	 * Starts a hook under the owning session's tool policy.
	 */
	@FunctionalInterface
	public interface Launcher
	{
		/**
		 * Starts the process in the copied workspace, with the configured event pipes.
		 *
		 * @param builder the caller's process arguments and environment
		 * @param directory the comparison directory
		 * @param trialHome the isolated trial home, or empty for production
		 * @return the running hook process
		 * @throws IOException if the hook cannot start under the owner's policy
		 */
		Process start(ProcessBuilder builder, Path directory, Optional<Path> trialHome) throws IOException;
	}

	/**
	 * The result of running the hooks.
	 *
	 * @param failures each hook that succeeds in the user's sessions and fails in trials, as a sentence fragment
	 * @param report   the number of hooks that ran and those whose output differs
	 * @param unapproved the native inspection's approval limitations, when observed
	 */
	public record Result(List<String> failures, HookReport report, Optional<List<String>> unapproved)
	{
		/**
		 * Creates a result.
		 *
		 * @param failures each hook that succeeds in the user's sessions and fails in trials, as a sentence fragment
		 * @param report   the number of hooks that ran and those whose output differs
		 * @param unapproved the native inspection's approval limitations, when observed
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Result
		{
			requireThat(failures, "failures").isNotNull();
			requireThat(report, "report").isNotNull();
			failures = List.copyOf(failures);
			unapproved = unapproved.map(List::copyOf);
		}
	}
}
