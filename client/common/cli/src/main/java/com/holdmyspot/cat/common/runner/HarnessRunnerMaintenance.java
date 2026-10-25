package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.FileReplacer;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Clock;
import java.util.Map;

/**
 * Recognizes and executes runner lifecycle commands without starting a harness process.
 */
public final class HarnessRunnerMaintenance
{
	/**
	 * Prevents utility-class construction.
	 */
	private HarnessRunnerMaintenance()
	{
	}

	/**
	 * Indicates whether the first argument selects a supported maintenance operation.
	 *
	 * @param args the command-line arguments
	 * @return {@code true} if the first argument names a maintenance operation
	 * @throws NullPointerException if {@code args} is null
	 */
	public static boolean isMaintenanceCommand(String[] args)
	{
		requireThat(args, "args").isNotNull();
		if (args.length == 0)
			return false;
		return switch (args[0])
		{
			case "--delete-run", "--fail-run", "--delete-stale-runs", "--prune-terminal-runtimes",
				"--verify-dependency-cache" -> true;
			default -> false;
		};
	}

	/**
	 * Executes one maintenance command for the selected harness.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param args the lifecycle command and its options
	 * @param harness the non-blank harness identifier used to select owned runs
	 * @param temporaryRoot where the runs are kept when the command line names no {@code --run-root}
	 * @param environment the parent environment used to verify the dependency cache
	 * @param out receives the verified cache path; other operations produce no output
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the command is invalid, if {@code harness} is blank, or if the operation
	 *   acts on runs while the command line names no {@code --run-root} outside a harness session
	 * @throws IOException if run maintenance or dependency-cache verification fails
	 */
	public static void execute(FileLocks fileLocks, String[] args, String harness, HarnessTemporaryRoot temporaryRoot,
		Map<String, String> environment,
		PrintStream out) throws IOException
	{
		requireThat(fileLocks, "fileLocks").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(out, "out").isNotNull();
		HarnessRunnerMaintenanceOptions options = HarnessRunnerMaintenanceOptions.parse(args, temporaryRoot);
		if (options.operation() == HarnessRunnerMaintenanceOptions.Operation.VERIFY_DEPENDENCY_CACHE)
		{
			out.println(HarnessDependencyCache.verifyNpm(environment));
			return;
		}
		HarnessRunManager manager = new HarnessRunManager(fileLocks, options.runRoot(), Clock.systemUTC(),
			FileReplacer.sleeping());
		switch (options.operation())
		{
			case DELETE_RUN -> manager.deleteRun(harness, options.runId());
			case FAIL_RUN -> manager.fail(harness, options.runId(), options.diagnostic(), options.exitCode());
			case DELETE_STALE_RUNS -> manager.deleteStaleRuns(options.retention());
			case PRUNE_TERMINAL_RUNTIMES -> manager.pruneTerminalRuntimes(harness);
			case VERIFY_DEPENDENCY_CACHE -> throw new AssertionError("Handled above");
		}
	}
}
