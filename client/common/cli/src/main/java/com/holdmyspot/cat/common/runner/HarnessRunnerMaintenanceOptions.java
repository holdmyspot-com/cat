package com.holdmyspot.cat.common.runner;

import java.nio.file.Path;
import java.time.Duration;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Represents command-line settings for one isolated-run lifecycle operation.
 *
 * @param operation the requested lifecycle operation
 *
 * @param runRoot the absolute owned-run root, or {@code null} for {@link Operation#VERIFY_DEPENDENCY_CACHE}
 *   when the command line names no {@code --run-root}
 *
 * @param runId the generated run identifier for an explicit operation, or {@code null}
 *
 * @param retention the stale-run retention period, or {@code null}
 *
 * @param diagnostic the externally observed failure diagnostic, or {@code null}
 *
 * @param exitCode the externally observed process exit status, or {@code null}
 */
public record HarnessRunnerMaintenanceOptions(Operation operation, Path runRoot, String runId, Duration retention,
	String diagnostic, Integer exitCode)
{
	/**
	 * Runner lifecycle operations that do not start a harness process.
	 */
	public enum Operation
	{
		/**
		 * Deletes one explicit owned run.
		 */
		DELETE_RUN,
		/**
		 * Records a terminal failure reported by an external supervisor.
		 */
		FAIL_RUN,
		/**
		 * Deletes eligible stale owned runs.
		 */
		DELETE_STALE_RUNS,
		/**
		 * Removes disposable runtime state from terminal retained runs while preserving their evidence.
		 */
		PRUNE_TERMINAL_RUNTIMES,
		/**
		 * Verifies the shared dependency cache without starting a harness.
		 */
		VERIFY_DEPENDENCY_CACHE
	}

	/**
	 * Parses one lifecycle-only runner command.
	 *
	 * @param args command-line arguments
	 *
	 * @param temporaryRoot where the runs are kept when the command line names no {@code --run-root}
	 *
	 * @return parsed maintenance options
	 *
	 * @throws IllegalArgumentException if the command is incomplete or invalid, or if an operation that acts on runs
	 *   names no {@code --run-root} outside a harness session
	 */
	public static HarnessRunnerMaintenanceOptions parse(String[] args, HarnessTemporaryRoot temporaryRoot)
	{
		requireThat(args, "args").isNotNull();
		if (args.length == 0)
			throw new IllegalArgumentException("Missing lifecycle command");
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		Operation operation = operation(args[0]);
		String explicitRunRoot = null;
		String runId = null;
		Duration retention = HarnessRunManager.DEFAULT_RETENTION;
		String diagnostic = null;
		Integer exitCode = null;
		int index = 1;
		while (index < args.length)
		{
			String option = args[index];
			if (index + 1 >= args.length)
				throw new IllegalArgumentException("Missing value for " + option);
			String value = args[index + 1];
			if (option.equals("--run-root"))
				explicitRunRoot = value;
			else if (option.equals("--run-id") && operation != Operation.DELETE_STALE_RUNS)
				runId = value;
			else if (option.equals("--diagnostic") && operation == Operation.FAIL_RUN)
				diagnostic = value;
			else if (option.equals("--exit-code") && operation == Operation.FAIL_RUN)
				exitCode = Integer.valueOf(value);
			else if (option.equals("--older-than") && operation == Operation.DELETE_STALE_RUNS)
				retention = Duration.parse(value);
			else
				throw new IllegalArgumentException("Unsupported option: " + option);
			index += 2;
		}
		Path runRoot;
		if (operation == Operation.VERIFY_DEPENDENCY_CACHE && explicitRunRoot == null)
			runRoot = null;
		else
			runRoot = temporaryRoot.runRoot(explicitRunRoot);
		if (operation == Operation.DELETE_STALE_RUNS)
			return new HarnessRunnerMaintenanceOptions(operation, runRoot, null, retention, null, null);
		if (operation == Operation.PRUNE_TERMINAL_RUNTIMES)
			return new HarnessRunnerMaintenanceOptions(operation, runRoot, null, null, null, null);
		if (operation == Operation.VERIFY_DEPENDENCY_CACHE)
			return new HarnessRunnerMaintenanceOptions(operation, runRoot, null, null, null, null);
		return new HarnessRunnerMaintenanceOptions(operation, runRoot, runId, null, diagnostic, exitCode);
	}

	/**
	 * Validates parsed maintenance options.
	 *
	 * @param operation the requested lifecycle operation
	 *
	 * @param runRoot the absolute owned-run root, or {@code null} for {@link Operation#VERIFY_DEPENDENCY_CACHE}
	 *
	 * @param runId the generated run identifier for an explicit operation, or {@code null}
	 *
	 * @param retention the stale-run retention period, or {@code null}
	 *
	 * @param diagnostic the externally observed failure diagnostic, or {@code null}
	 *
	 * @param exitCode the externally observed process exit status, or {@code null}
	 */
	public HarnessRunnerMaintenanceOptions
	{
		requireThat(operation, "operation").isNotNull();
		if (runRoot == null)
		{
			if (operation != Operation.VERIFY_DEPENDENCY_CACHE)
				throw new NullPointerException("runRoot may not be null");
		}
		else if (!runRoot.isAbsolute())
			throw new IllegalArgumentException("runRoot must be absolute");
		if (operation == Operation.DELETE_RUN)
		{
			requireThat(runId, "runId").isNotBlank();
			if (retention != null || diagnostic != null || exitCode != null)
				throw new IllegalArgumentException("delete-run does not accept a retention period");
		}
		else if (operation == Operation.FAIL_RUN)
		{
			requireThat(runId, "runId").isNotBlank();
			requireThat(diagnostic, "diagnostic").isNotBlank();
			requireThat(exitCode, "exitCode").isNotNull().isNotNegative();
			if (retention != null)
				throw new IllegalArgumentException("fail-run does not accept a retention period");
		}
		else if (operation == Operation.DELETE_STALE_RUNS)
		{
			if (runId != null || retention == null || retention.isNegative() || retention.isZero() || diagnostic !=
				null ||
				exitCode != null)
				throw new IllegalArgumentException("delete-stale-runs requires a positive retention period only");
		}
		else if (runId != null || retention != null || diagnostic != null || exitCode != null)
			throw new IllegalArgumentException("This lifecycle operation does not accept additional options");
	}

	/**
	 * Maps a command token to its lifecycle operation.
	 *
	 * @param command the command token
	 *
	 * @return the matching operation
	 */
	private static Operation operation(String command)
	{
		assert command != null;
		if (command.equals("--delete-run"))
			return Operation.DELETE_RUN;
		if (command.equals("--fail-run"))
			return Operation.FAIL_RUN;
		if (command.equals("--delete-stale-runs"))
			return Operation.DELETE_STALE_RUNS;
		if (command.equals("--prune-terminal-runtimes"))
			return Operation.PRUNE_TERMINAL_RUNTIMES;
		if (command.equals("--verify-dependency-cache"))
			return Operation.VERIFY_DEPENDENCY_CACHE;
		throw new IllegalArgumentException("Unsupported lifecycle command: " + command);
	}
}
