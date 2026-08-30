package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessDependencyCache;
import io.github.cowwoc.cat.common.runner.HarnessArtifact;
import io.github.cowwoc.cat.common.runner.HarnessRunReceipt;
import io.github.cowwoc.cat.common.runner.HarnessRunStarter;
import io.github.cowwoc.cat.common.runner.HarnessConcurrencyLimit;
import io.github.cowwoc.cat.common.runner.HarnessRunnerBridge;
import io.github.cowwoc.cat.common.runner.HarnessRunnerMaintenanceOptions;
import io.github.cowwoc.cat.common.runner.HarnessRunnerOptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs the Codex JSONL bridge or maintains isolated execution directories.
 */
public final class CodexRunnerCli
{
	/**
	 * Serves JSONL turns through one persistent isolated Codex process.
	 *
	 * @param args bridge options
	 * @throws IOException if runner startup, the harness, or bridge I/O fails
	 */
	public static void main(String[] args) throws IOException
	{
		requireThat(args, "args").isNotNull();
		if (args.length == 1 && args[0].equals("--help"))
		{
			System.out.println(HarnessRunnerOptions.usage("codex-runner"));
			return;
		}
		if (args.length > 0 && args[0].equals("--resolve-profile"))
		{
			System.out.print(resolveProfile(args));
			return;
		}
		if (args.length == 2 && args[0].equals("--verify-artifact"))
		{
			System.out.println(HarnessArtifact.validate(Path.of(args[1]), Path.of(".codex-plugin/plugin.json")));
			return;
		}
		if (isMaintenanceCommand(args))
		{
			executeMaintenance(args);
			return;
		}
		serve(args, new BufferedReader(new InputStreamReader(System.in, UTF_8)),
			new OutputStreamWriter(System.out, UTF_8), System.getenv());
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
			HarnessRunnerMaintenanceOptions options = HarnessRunnerMaintenanceOptions.parse(args, scope.temporaryWorkRoot());
			HarnessRunManager manager = new HarnessRunManager(options.runRoot(), Clock.systemUTC());
			if (options.operation() == HarnessRunnerMaintenanceOptions.Operation.DELETE_RUN)
				manager.deleteRun("codex", options.runId());
			else if (options.operation() == HarnessRunnerMaintenanceOptions.Operation.FAIL_RUN)
				manager.fail("codex", options.runId(), options.diagnostic(), options.exitCode());
			else if (options.operation() == HarnessRunnerMaintenanceOptions.Operation.PRUNE_TERMINAL_RUNTIMES)
				manager.pruneTerminalRuntimes("codex");
			else if (options.operation() == HarnessRunnerMaintenanceOptions.Operation.VERIFY_DEPENDENCY_CACHE)
				System.out.println(HarnessDependencyCache.verifyNpm(System.getenv()));
			else
				manager.deleteStaleRuns(options.retention());
		}
	}

	/**
	 * Serves one Codex bridge using caller-provided streams and environment.
	 *
	 * @param args bridge options
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
			String agentName = agentName(args);
		HarnessRunnerOptions options = HarnessRunnerOptions.parse(withoutCodexOptions(args), "codex",
			scope.temporaryWorkRoot());
		CodexAgentDefinition agent = loadAgent(options.artifact(), agentName);
		List<Path> readOnlyEvidence = readOnlyEvidence(args);
			HarnessRunManager manager = new HarnessRunManager(options.runRoot(), Clock.systemUTC());
			manager.deleteStaleRuns(HarnessRunManager.DEFAULT_RETENTION);
		serve(options, agent, readOnlyEvidence, manager, input, output, environment, scope.json());
		}
	}

	/**
	 * Runs one configured bridge and directs its JSONL transcript to the requested destination.
	 *
	 * @param options parsed shared bridge options
	 * @param agent optional direct CAT agent
	 * @param readOnlyEvidence optional completed executor evidence
	 * @param manager owned-run lifecycle manager
	 * @param input bridge input
	 * @param output bridge output when no durable destination was requested
	 * @param environment host environment
	 * @param json the operation-owned receipt mapper
	 * @throws IOException if bridge execution or retained-evidence writing fails
	 */
	private static void serve(HarnessRunnerOptions options, CodexAgentDefinition agent, List<Path> readOnlyEvidence,
		HarnessRunManager manager,
		Reader input, Writer output, Map<String, String> environment, tools.jackson.databind.json.JsonMapper json)
		throws IOException
	{
		assert options != null;
		assert manager != null;
		assert input != null;
		assert output != null;
		assert environment != null;
		assert json != null;
		try (HarnessConcurrencyLimit capacity = HarnessConcurrencyLimit.acquire(options.maximumConcurrency()))
		{
			capacity.verifyHeld();
			HarnessRunStarter starter = () -> new CodexRunner().start(options.artifact(), options.project(), manager,
				options.executable(), environment, options.model(), options.effort(), options.installCat(),
				options.includeUserRules(), agent, readOnlyEvidence);
				HarnessRun run;
				if (options.output() == null)
					run = HarnessRunnerBridge.serve(starter, manager, options.keepRun(), input, output);
				else
					run = HarnessRunnerBridge.serve(starter, manager, options.keepRun(), input, options.output());
				if (options.receipt() != null)
				{
					HarnessRunReceipt.write(json, options.receipt(), run, options.output());
					manager.pruneRuntime(run);
				}
		}
	}

	/**
	 * Prevents utility-class construction.
	 */
	private CodexRunnerCli()
	{
	}

	/**
	 * Resolves the configured CAT default or a selected CAT agent profile without starting a harness process.
	 *
	 * @param args the profile-resolution options
	 * @return a stable scalar profile result
	 * @throws IOException if the selected agent cannot be read
	 */
	static String resolveProfile(String[] args) throws IOException
	{
		requireThat(args, "args").isNotNull();
		if (args.length == 1)
			return profile("default", "gpt-5.6-luna", "medium");
		if (args.length != 5 || !args[1].equals("--artifact") || !args[3].equals("--agent"))
			throw new IllegalArgumentException("Usage: codex-runner --resolve-profile [--artifact <path> --agent <name>]");
		CodexAgentDefinition agent = CodexAgentDefinition.load(Path.of(args[2]), args[4]);
		return profile("agent:" + args[4], agent.model(), agent.effort());
	}

	/**
	 * Serializes one runner profile as a stable scalar result.
	 *
	 * @param owner the profile owner
	 * @param model the model name
	 * @param effort the reasoning effort
	 * @return the complete profile result
	 */
	private static String profile(String owner, String model, String effort)
	{
		assert owner != null;
		assert model != null;
		assert effort != null;
		return "harness=codex\nowner=" + owner + "\nmodel=" + model + "\neffort=" + effort + "\n";
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
			args[0].equals("--fail-run") || args[0].equals("--prune-terminal-runtimes") ||
			args[0].equals("--verify-dependency-cache"));
	}

	/**
	 * Extracts the optional direct CAT agent name from one runner command.
	 *
	 * @param args the supplied command arguments
	 * @return the selected agent name, or an empty value
	 */
	private static String agentName(String[] args)
	{
		assert args != null;
		for (int index = 1; index < args.length; ++index)
		{
			if (!args[index].equals("--agent"))
				continue;
			if (index + 1 == args.length || args[index + 1].startsWith("--"))
				throw new IllegalArgumentException("Missing value for --agent");
			return args[index + 1];
		}
		return "";
	}

	/**
	 * Loads the requested CAT agent when one was selected.
	 *
	 * @param artifact the complete Codex artifact
	 * @param agentName the optional agent name
	 * @return the agent definition, or {@code null} when no agent was selected
	 * @throws IOException if the selected agent cannot be loaded
	 */
	private static CodexAgentDefinition loadAgent(Path artifact, String agentName) throws IOException
	{
		assert artifact != null;
		assert agentName != null;
		if (agentName.isBlank())
			return null;
		return CodexAgentDefinition.load(artifact, agentName);
	}

	/**
	 * Resolves the optional directory that a validator may inspect without write authority.
	 *
	 * @param args the supplied command line
	 * @return the canonical evidence directory, or {@code null}
	 * @throws IOException if the requested path is absent or cannot be resolved
	 */
	private static List<Path> readOnlyEvidence(String[] args) throws IOException
	{
		List<Path> result = new ArrayList<>();
		for (int index = 0; index < args.length; ++index)
			if (args[index].equals("--read-only-evidence"))
			{
				if (index + 1 == args.length || args[index + 1].startsWith("--"))
					throw new IOException("Missing path after --read-only-evidence");
				result.add(Path.of(args[index + 1]).toRealPath());
				index += 1;
			}
		return result;
	}

	/**
	 * Removes Codex-only options before parsing the shared runner options.
	 *
	 * @param args the supplied command arguments
	 * @return arguments accepted by the shared parser
	 */
	private static String[] withoutCodexOptions(String[] args)
	{
		assert args != null;
		List<String> result = new ArrayList<>(args.length);
		for (int index = 0; index < args.length; ++index)
		{
			if (args[index].equals("--agent") || args[index].equals("--read-only-evidence"))
			{
				index += 1;
				continue;
			}
			result.add(args[index]);
		}
		return result.toArray(String[]::new);
	}
}
