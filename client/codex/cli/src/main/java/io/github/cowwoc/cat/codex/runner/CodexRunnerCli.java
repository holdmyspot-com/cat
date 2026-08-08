package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessOneShotOptions;
import io.github.cowwoc.cat.common.runner.HarnessOneShotRunner;
import io.github.cowwoc.cat.common.runner.HarnessRunnerBridge;
import io.github.cowwoc.cat.common.runner.HarnessRunnerMaintenanceOptions;
import io.github.cowwoc.cat.common.runner.HarnessRunnerOptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.time.Clock;
import java.util.Map;

/**
 * Parses Codex runner commands to serve JSON requests, run one prompt, or maintain isolated execution directories.
 */
public final class CodexRunnerCli
{
	/**
	 * Serves JSONL turns through one persistent isolated Codex process.
	 *
	 * @param args the {@code serve} command and its options
	 * @throws IOException if runner startup, the harness, or bridge I/O fails
	 */
	public static void main(String[] args) throws IOException
	{
		requireThat(args, "args").isNotNull();
		if (isMaintenanceCommand(args))
		{
			executeMaintenance(args);
			return;
		}
		if (isOneShotCommand(args))
		{
			run(args, System.getenv());
			return;
		}
		serve(args, new BufferedReader(new InputStreamReader(System.in, UTF_8)),
			new OutputStreamWriter(System.out, UTF_8), System.getenv());
	}

	/**
	 * Executes one prompt through an isolated Codex session.
	 *
	 * @param args the {@code run} command and its options
	 * @param environment the host environment
	 * @throws IOException if the prompt, harness, output, or cleanup I/O fails
	 */
	public static void run(String[] args, Map<String, String> environment) throws IOException
	{
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		try (ToolScope scope = new ToolScope())
		{
			HarnessOneShotOptions options = HarnessOneShotOptions.parse(args, "codex", scope.catTemp());
			HarnessRunManager manager = new HarnessRunManager(options.runRoot(), Clock.systemUTC());
			String prompt = options.prompt();
			HarnessOneShotRunner.run(() -> new CodexRunner().start(options.artifact(), options.project(), manager,
				options.executable(), environment, options.model(), options.installCat()), manager, prompt, options.output(),
				options.keepRun());
		}
	}

	/**
	 * Runs one lifecycle-only command without starting Codex.
	 *
	 * @param args lifecycle command arguments
	 * @throws IOException if owned-run cleanup fails
	 */
	public static void executeMaintenance(String[] args) throws IOException
	{
		requireThat(args, "args").isNotNull();
		try (ToolScope scope = new ToolScope())
		{
			HarnessRunnerMaintenanceOptions options = HarnessRunnerMaintenanceOptions.parse(args, scope.catTemp());
			HarnessRunManager manager = new HarnessRunManager(options.runRoot(), Clock.systemUTC());
			if (options.operation() == HarnessRunnerMaintenanceOptions.Operation.DELETE_RUN)
				manager.deleteRun("codex", options.runId());
			else if (options.operation() == HarnessRunnerMaintenanceOptions.Operation.FAIL_RUN)
				manager.fail("codex", options.runId(), options.diagnostic(), options.exitCode());
			else
				manager.deleteStaleRuns(options.retention());
		}
	}

	/**
	 * Serves one Codex bridge using caller-provided streams and environment.
	 *
	 * @param args the {@code serve} command and its options
	 * @param input the bridge input
	 * @param output the bridge output
	 * @param environment the host environment
	 * @throws IOException if runner startup, the harness, or bridge I/O fails
	 */
	public static void serve(String[] args, Reader input, Writer output, Map<String, String> environment)
		throws IOException
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(environment, "environment").isNotNull();
		try (ToolScope scope = new ToolScope())
		{
			HarnessRunnerOptions options = HarnessRunnerOptions.parseServe(args, "codex", scope.catTemp());
			HarnessRunManager manager = new HarnessRunManager(options.runRoot(), Clock.systemUTC());
			HarnessRunnerBridge.serve(() -> new CodexRunner().start(options.artifact(), options.project(), manager,
				options.executable(), environment, options.model(), options.installCat()), manager, options.keepRun(), input,
				output);
		}
	}

	/**
	 * Prevents utility-class construction.
	 */
	private CodexRunnerCli()
	{
	}

	/**
	 * Determines whether the arguments select a lifecycle-only command.
	 *
	 * @param args command-line arguments
	 * @return {@code true} if the first argument is a lifecycle command
	 */
	private static boolean isMaintenanceCommand(String[] args)
	{
		assert args != null;
		return args.length > 0 && (args[0].equals("--delete-run") || args[0].equals("--delete-stale-runs") ||
			args[0].equals("--fail-run"));
	}

	/**
	 * Determines whether the arguments select a one-shot run.
	 *
	 * @param args command-line arguments
	 * @return {@code true} if the first argument is {@code run}
	 */
	private static boolean isOneShotCommand(String[] args)
	{
		assert args != null;
		return args.length > 0 && args[0].equals("run");
	}
}
