package com.holdmyspot.cat.claude.runner;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.HarnessRunStarter;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.RequestedRunner;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.launcher.client.SessionLauncherClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the Claude JSONL bridge.
 */
public final class ClaudeRunnerCli
{
	/**
	 * The option that names the file of the policy that bounds the session's sandbox policy.
	 */
	private static final String POLICY_BOUND_OPTION = "--policy-bound";
	/**
	 * The name of the file, in a requested session's response directory, that holds the requested session's bound.
	 */
	private static final String POLICY_BOUND_FILE = "policy-bound.json";
	/**
	 * The version of the format of a policy bound's file.
	 */
	private static final int POLICY_BOUND_FORMAT = 1;
	/**
	 * The fields of a policy bound's file.
	 */
	private static final Set<String> POLICY_BOUND_FIELDS = Set.of("format_version", "write_roots", "write_denials",
		"read_denials", "read_reallowances", "hosts", "allow_local_binding");
	/**
	 * The longest policy bound's file that is read.
	 */
	private static final int MAXIMUM_POLICY_BOUND_BYTES = 1_048_576;
	/**
	 * Prints the usage, verifies an artifact, or serves JSONL turns through one persistent isolated Claude process, as
	 * the arguments select. The launcher always runs the artifact that holds it and the {@code claude} command that the
	 * {@code PATH} finds: before anything else, it refuses {@code --artifact} with exit status
	 * {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} unless it forwards the launch as a session request. It also
	 * refuses, with the same status, any launch that starts a session on a platform where Claude Code cannot sandbox
	 * the session.
	 * <p>
	 * Inside a run whose runner lets its session request sessions, the launch becomes a session request instead: the
	 * run's own runner starts the session outside the run's sandbox, and this command returns its transcript and
	 * receipt. Outside a run, the launch becomes a request to the session launcher of the harness process that runs
	 * the command, which starts the session outside the command's sandbox, unless a session launcher started the
	 * launch itself: that launch names {@value SessionLauncherClient#POLICY_BOUND_OPTION} and serves the session in
	 * place.
	 *
	 * @param args the command's options
	 * @throws IOException if runner startup, the harness, or bridge I/O fails
	 */
	public static void main(String[] args) throws IOException
	{
		args = CommandLineArguments.recover(args);
		Map<String, String> environment = Platform.current().environment(System.getenv());
		if (refusesLaunch(args, environment, Platform.current(), new PrintWriter(System.err, true, UTF_8)))
			System.exit(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
		if (args.length == 1 && args[0].equals("--help"))
		{
			System.out.println(HarnessRunnerOptions.usage("claude-runner"));
			return;
		}
		if (args.length == 2 && args[0].equals("--verify-artifact"))
		{
			System.out.println(HarnessArtifact.validate(Path.of(args[1]), Path.of(".claude-plugin/plugin.json")));
			return;
		}
		String requests = environment.get(SessionRequestAccess.REQUESTS_VARIABLE);
		if (requests != null && !requests.isBlank())
		{
			System.exit(SessionRequestForwarder.forward(HARNESS_ID, args,
				new InputStreamReader(System.in, UTF_8), Path.of(requests), Pause.sleeping(Duration.ofMillis(100)),
				System.out, new PrintWriter(System.err, true, UTF_8)));
		}
		if (SessionLauncherClient.serves(Platform.current(), args))
		{
			System.exit(SessionLauncherClient.launchFromMain(HARNESS_ID, args, environment, Pause::sleeping,
				new InputStreamReader(System.in, UTF_8), System.out,
				new PrintWriter(System.err, true, UTF_8)));
		}
		args = HarnessRunnerOptions.withArtifact(args, PluginDirectory.of(ClaudeRunnerCli.class));
		Path home = Platform.current().homeDirectory(environment);
		try (JvmScope process = new MainJvmScope(environment, Pause::sleeping))
		{
			serve(process, Path.of(""), args, new BufferedReader(new InputStreamReader(System.in, UTF_8)),
				new OutputStreamWriter(System.out, UTF_8), environment, maximum -> HarnessCapacity.forHome(
					process.fileLocks(), home, HARNESS_ID, maximum, process.waiting()));
		}
		catch (HarnessCapacityWaitExpiredException e)
		{
			System.err.println(e.getMessage());
			System.exit(HarnessCapacityWaitExpiredException.EXIT_STATUS);
		}
		catch (SessionRefusedException e)
		{
			System.err.println(e.getMessage());
			System.exit(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
		}
	}

	/**
	 * Refuses a launch that names {@code --artifact}, other than inside a run whose runner lets its session request
	 * sessions. There every launch becomes a session request, whose runner ignores the option.
	 *
	 * @param args        the launch's options
	 * @param environment the launcher's environment
	 * @param errors      receives the reason for a refusal
	 * @return {@code true} if the launch is refused
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean refusesOverride(String[] args, Map<String, String> environment, PrintWriter errors)
	{
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(errors, "errors").isNotNull();
		String requests = environment.get(SessionRequestAccess.REQUESTS_VARIABLE);
		if (requests != null && !requests.isBlank())
			return false;
		return HarnessRunnerOptions.refusesOverride(HARNESS_ID, args, errors);
	}

	/**
	 * Refuses a launch that would start a session, but must not. That is a launch that names {@code --artifact}, as
	 * {@link #refusesOverride} refuses it, and any launch on a platform where Claude Code cannot confine the session
	 * in a sandbox, as {@link ClaudeSessionSandbox} decides. Printing the usage and verifying an artifact start no
	 * session, so they are never refused for the platform.
	 *
	 * @param args        the launch's options
	 * @param environment the launcher's environment
	 * @param platform    the platform that the launcher runs on
	 * @param errors      receives the reason for a refusal
	 * @return {@code true} if the launch is refused
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean refusesLaunch(String[] args, Map<String, String> environment, Platform platform,
		PrintWriter errors)
	{
		requireThat(platform, "platform").isNotNull();
		if (refusesOverride(args, environment, errors))
			return true;
		boolean startsNothing = args.length == 1 && args[0].equals("--help") ||
			args.length == 2 && args[0].equals("--verify-artifact");
		if (startsNothing)
			return false;
		Optional<String> refusal = ClaudeSessionSandbox.refusal(platform);
		if (refusal.isEmpty())
			return false;
		errors.println(refusal.get());
		return true;
	}

	/**
	 * Serves one Claude bridge using caller-provided streams and environment.
	 * <p>
	 * {@code --policy-bound} names the file of the policy that bounds the session's sandbox policy, which the session
	 * launcher writes. Every session starts through the session launcher, so a launch without it is refused before
	 * it takes a capacity slot or creates a run. A session that the bounded session requests is bounded in turn by the
	 * bounded session's policy, widened only by the requested session's own run folder.
	 *
	 * @param process            the process scope, whose lock coordinator and waiting the bridge uses
	 * @param workingDirectory   the directory from which the command was invoked
	 * @param args               bridge options
	 * @param input              the bridge input
	 * @param output             the bridge output
	 * @param environment        the host environment
	 * @param capacityForMaximum creates the shared harness capacity for the {@code --max-concurrency} value that the
	 *                           command selected
	 * @throws NullPointerException    if any argument is null
	 * @throws SessionRefusedException if the launch names no {@code --policy-bound}, or the session's sandbox would
	 *                                 not confine it as its policy requires
	 * @throws IOException             if the policy bound cannot be read, or runner startup, the harness, or bridge
	 *                                 I/O fails
	 */
	public static void serve(JvmScope process, Path workingDirectory, String[] args, Reader input, Writer output,
		Map<String, String> environment, IntFunction<HarnessCapacity> capacityForMaximum) throws IOException
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(process, "process").isNotNull();
		requireThat(capacityForMaximum, "capacityForMaximum").isNotNull();
		try (ToolScope scope = ToolScope.forSession(workingDirectory, environment, SessionHarness.installed(),
			process.waiting()))
		{
			HarnessRunnerOptions options = options(args, HarnessTemporaryRoot.from(scope));
			ClaudeAgentDefinition agent = loadAgent(options.artifact(), agentName(args));
			List<Path> readOnlyEvidence = readOnlyEvidence(args);
			String instructionFilesMode = instructionFilesMode(args);
			ChildPolicy bound = policyBound(scope.json(), args);
			FileReplacer replacer = FileReplacer.retrying(process.waiting());
			HarnessRunManager manager = HarnessRunManager.openRunRoot(process.fileLocks(), options.runRoot(),
				Clock.systemUTC(), replacer);
			serve(scope, options, agent, readOnlyEvidence, instructionFilesMode, bound, manager, input, output,
				environment, replacer, capacityForMaximum.apply(options.maximumConcurrency()));
		}
	}

	/**
	 * Runs one configured bridge and directs its JSONL transcript to the requested destination.
	 *
	 * @param scope the borrowed command scope
	 * @param options parsed shared bridge options
	 * @param agent optional direct CAT agent
	 * @param readOnlyEvidence completed executor evidence visible without write authority
	 * @param instructionFilesMode the choice of which instruction files load, or an empty string to keep the parent's
	 *   choice
	 * @param bound the policy that bounds the session's sandbox policy, or {@code null} if nothing bounds it
	 * @param manager owned-run lifecycle manager
	 * @param input bridge input
	 * @param output bridge output when no durable destination was requested
	 * @param environment host environment
	 * @param replacer replaces the receipt
	 * @param capacity the shared harness capacity
	 * @throws IOException if bridge execution or retained-evidence writing fails
	 */
	private static void serve(ToolScope scope, HarnessRunnerOptions options, ClaudeAgentDefinition agent,
		List<Path> readOnlyEvidence,
		String instructionFilesMode, ChildPolicy bound, HarnessRunManager manager,
		Reader input, Writer output,
		Map<String, String> environment, FileReplacer replacer,
		HarnessCapacity capacity) throws IOException
	{
		assert options != null;
		assert instructionFilesMode != null;
		assert manager != null;
		assert input != null;
		assert output != null;
		assert environment != null;
		assert scope != null;
		assert replacer != null;
		assert capacity != null;
		try (HarnessCapacity.Permit _ = acquire(capacity, options))
		{
			AtomicReference<ChildPolicy> policy = new AtomicReference<>();
			HarnessRunStarter starter = () -> new ClaudeRunner(scope.waiting()).start(
				options.artifact(), options.project(), options.workspace(), options.plugins(), manager, "claude",
				environment,
				options.model(),
				options.effort(), agent, options.installCat(), readOnlyEvidence,
				options.deniedReads(), instructionFilesMode, options.sessionTranscripts(), options.sessionRequests(),
				bound, policy::set);
			HarnessRunnerBridge.serve(starter, manager, options, scope.json(), replacer, input, output,
				(harness, arguments, directory) ->
			{
				RequestedRunner runner;
				if (harness.equals("claude"))
				{
					runner = new RequestedRunner(scope,
						options.artifact().toAbsolutePath().resolve("bin/claude-runner"), List.of());
				}
				else if (harness.equals("codex"))
					runner = RequestedRunner.resolved(scope, CodexArtifact.execute(environment), List.of("serve"));
				else
					throw new IllegalArgumentException("Unsupported requested harness: " + harness);
				return runner.start(boundArguments(scope.json(), arguments, directory, policy.get()), directory,
					environment);
			});
		}
	}

	/**
	 * Takes a capacity permit, waiting no longer than the command's {@code --capacity-wait} if it named one.
	 *
	 * @param capacity the shared harness capacity
	 * @param options the parsed bridge options
	 * @return the permit
	 * @throws HarnessCapacityWaitExpiredException if the wait passed without a free permit
	 * @throws IOException if a slot cannot be locked or the wait fails
	 */
	private static HarnessCapacity.Permit acquire(HarnessCapacity capacity, HarnessRunnerOptions options)
		throws IOException
	{
		if (options.capacityWait() == null)
			return capacity.acquire();
		return capacity.acquire(options.capacityWait());
	}

	/**
	 * Prevents utility-class construction.
	 */
	private ClaudeRunnerCli()
	{
	}

	/**
	 * Parses the options of a bridge command, other than the Claude-only {@code --read-only-evidence} and
	 * {@code --instruction-files}. A command that selects a CAT agent definition with {@code --agent} runs under the
	 * model and effort that the definition names.
	 *
	 * @param args          the bridge command's options
	 * @param temporaryRoot where the runs are kept when the command names no {@code --run-root}
	 * @return the parsed options
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if an option is unknown, has no value or has an invalid value, if the command
	 *                                  names {@code --agent} together with {@code --model} or {@code --effort}, or if
	 *                                  it names neither {@code --agent} nor both {@code --model} and {@code --effort}
	 * @throws IOException              if the selected agent definition is absent or does not name both a model and
	 *                                  an effort
	 */
	public static HarnessRunnerOptions options(String[] args, HarnessTemporaryRoot temporaryRoot)
		throws IOException
	{
		requireThat(args, "args").isNotNull();
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		String[] runnerArgs = withoutClaudeOptions(args);
		String agentName = agentName(args);
		if (!agentName.isBlank())
		{
			HarnessRunnerOptions.requireNoProfileBesideAgent(runnerArgs);
			Path artifact = HarnessRunnerOptions.artifactOption(runnerArgs);
			if (artifact != null)
			{
				ClaudeAgentDefinition agent = ClaudeAgentDefinition.load(artifact, agentName);
				runnerArgs = HarnessRunnerOptions.withAgentProfile(runnerArgs, agent.model(), agent.effort());
			}
		}
		return HarnessRunnerOptions.parse(runnerArgs, "claude", temporaryRoot);
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
	 * @param artifact the complete Claude artifact
	 * @param agentName the optional agent name
	 * @return the agent definition, or {@code null} when no agent was selected
	 * @throws IOException if the selected agent cannot be loaded
	 */
	private static ClaudeAgentDefinition loadAgent(Path artifact, String agentName) throws IOException
	{
		assert artifact != null;
		assert agentName != null;
		if (agentName.isBlank())
			return null;
		return ClaudeAgentDefinition.load(artifact, agentName);
	}

	/**
	 * Resolves repeated immutable evidence options.
	 *
	 * @param args the command-line arguments
	 * @return canonical evidence paths
	 * @throws IOException if an evidence path cannot be resolved
	 */
	private static List<Path> readOnlyEvidence(String[] args) throws IOException
	{
		List<Path> result = new ArrayList<>();
		for (int index = 0; index < args.length; ++index)
			if (args[index].equals("--read-only-evidence"))
			{
				if (index + 1 == args.length || args[index + 1].startsWith("--"))
					throw new IOException("Missing path after --read-only-evidence");
				index += 1;
				result.add(Path.of(args[index]).toRealPath());
			}
		return result;
	}

	/**
	 * Extracts the optional choice of which instruction files Claude Code loads, such as {@code claude-md}.
	 *
	 * @param args the command-line arguments
	 * @return the choice, or an empty string to keep the parent's choice
	 * @throws IllegalArgumentException if {@code --instruction-files} has no value
	 */
	private static String instructionFilesMode(String[] args)
	{
		assert args != null;
		for (int index = 0; index < args.length; ++index)
		{
			if (!args[index].equals("--instruction-files"))
				continue;
			if (index + 1 == args.length || args[index + 1].startsWith("--"))
				throw new IllegalArgumentException("Missing value for --instruction-files");
			return args[index + 1];
		}
		return "";
	}

	/**
	 * Removes Claude-specific options before shared option parsing.
	 *
	 * @param args the command-line arguments
	 * @return arguments accepted by the shared parser
	 */
	private static String[] withoutClaudeOptions(String[] args)
	{
		List<String> result = new ArrayList<>(args.length);
		for (int index = 0; index < args.length; ++index)
		{
			if (args[index].equals("--read-only-evidence") || args[index].equals("--agent") ||
				args[index].equals("--instruction-files") || args[index].equals(POLICY_BOUND_OPTION))
			{
				index += 1;
				continue;
			}
			result.add(args[index]);
		}
		return result.toArray(String[]::new);
	}

	/**
	 * Reads the policy that bounds the session's sandbox policy.
	 *
	 * @param json the JSON mapper
	 * @param args the command-line arguments
	 * @return the bound
	 * @throws SessionRefusedException if the launch names no bound
	 * @throws IOException             if the bound's file cannot be read or is not a bound
	 */
	private static ChildPolicy policyBound(JsonMapper json, String[] args) throws IOException
	{
		String value = null;
		for (int index = 0; index < args.length; ++index)
		{
			if (!args[index].equals(POLICY_BOUND_OPTION))
				continue;
			if (index + 1 == args.length || args[index + 1].startsWith("--"))
				throw new IllegalArgumentException("Missing path after " + POLICY_BOUND_OPTION);
			value = args[index + 1];
		}
		if (value == null)
		{
			throw new SessionRefusedException("claude-runner starts a session only " +
				"through CAT's session launcher, which names the policy that bounds the session's sandbox with " +
				POLICY_BOUND_OPTION + ", and this launch names none, so CAT does not start the session. Start the " +
				"session from a Claude Code or Codex session in which CAT is installed");
		}
		return readPolicyBound(json, Path.of(value).toAbsolutePath());
	}

	/**
	 * Reads a policy bound's file: a JSON object whose {@code format_version} is {@value #POLICY_BOUND_FORMAT}, whose
	 * {@code write_roots}, {@code write_denials}, {@code read_denials} and {@code read_reallowances} are arrays of
	 * absolute paths, whose {@code hosts} is an array of host names, and whose {@code allow_local_binding} is a
	 * boolean, and that has no other field.
	 *
	 * @param json the JSON mapper
	 * @param file the file
	 * @return the bound
	 * @throws IOException if the file cannot be read or is not a bound
	 */
	private static ChildPolicy readPolicyBound(JsonMapper json, Path file) throws IOException
	{
		JsonNode bound;
		try
		{
			bound = json.readTree(BoundedInput.readUtf8(file, MAXIMUM_POLICY_BOUND_BYTES, "policy bound"));
		}
		catch (JacksonException e)
		{
			throw new IOException("The policy bound " + file + " is not valid JSON", e);
		}
		if (!bound.isObject())
			throw new IOException("The policy bound " + file + " is not a JSON object");
		for (String field : bound.propertyNames())
		{
			if (!POLICY_BOUND_FIELDS.contains(field))
				throw new IOException("The policy bound " + file + " has an unknown field: " + field);
		}
		for (String field : POLICY_BOUND_FIELDS)
		{
			if (!bound.has(field))
				throw new IOException("The policy bound " + file + " lacks the field " + field);
		}
		if (!bound.path("format_version").isInt() || bound.path("format_version").intValue() != POLICY_BOUND_FORMAT)
		{
			throw new IOException("The policy bound " + file + " has an unsupported format_version: " +
				bound.path("format_version"));
		}
		if (!bound.path("allow_local_binding").isBoolean())
			throw new IOException("The policy bound " + file + " has a non-boolean allow_local_binding");
		try
		{
			return new ChildPolicy(paths(bound, "write_roots", file), paths(bound, "write_denials", file),
				paths(bound, "read_denials", file), paths(bound, "read_reallowances", file),
				new LinkedHashSet<>(strings(bound, "hosts", file)), bound.path("allow_local_binding").booleanValue());
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("The policy bound " + file + " is not a valid policy: " + e.getMessage(), e);
		}
	}

	/**
	 * Reads an array of paths from a policy bound.
	 *
	 * @param bound the bound
	 * @param field the array's field
	 * @param file  the bound's file
	 * @return the paths
	 * @throws IOException if the field is not an array of text
	 */
	private static List<Path> paths(JsonNode bound, String field, Path file) throws IOException
	{
		List<Path> result = new ArrayList<>();
		for (String value : strings(bound, field, file))
			result.add(Path.of(value));
		return result;
	}

	/**
	 * Reads an array of text from a policy bound.
	 *
	 * @param bound the bound
	 * @param field the array's field
	 * @param file  the bound's file
	 * @return the text
	 * @throws IOException if the field is not an array of text
	 */
	private static List<String> strings(JsonNode bound, String field, Path file) throws IOException
	{
		JsonNode array = bound.path(field);
		if (!array.isArray())
			throw new IOException("The policy bound " + file + " has a non-array " + field);
		List<String> result = new ArrayList<>();
		for (JsonNode element : array)
		{
			if (!element.isString())
				throw new IOException("The policy bound " + file + " has a non-text element in " + field);
			result.add(element.stringValue());
		}
		return result;
	}

	/**
	 * Returns the arguments of a requested session's runner, with the policy that bounds the requested session: the
	 * requesting session's policy, widened only by the requested session's run root, which its runner writes in.
	 *
	 * @param json      the JSON mapper
	 * @param arguments the runner's arguments, which name its {@code --run-root}
	 * @param directory the requested session's response directory, which receives the bound's file
	 * @param policy    the requesting session's sandbox policy, or {@code null} if it has none yet
	 * @return the arguments, followed by {@code --policy-bound} and the bound's file when {@code policy} is not null
	 * @throws IOException if the arguments name no run root, or the bound's file cannot be written
	 */
	private static List<String> boundArguments(JsonMapper json, List<String> arguments, Path directory,
		ChildPolicy policy) throws IOException
	{
		if (policy == null)
			return arguments;
		int runRoot = arguments.indexOf("--run-root");
		if (runRoot < 0 || runRoot + 1 == arguments.size())
			throw new IOException("A requested session's runner arguments name no --run-root: " + arguments);
		ChildPolicy bound = ChildPolicy.intersect(ClaudeConnectionSettings.bounding(policy),
			new ChildNeeds(Path.of(arguments.get(runRoot + 1)).toAbsolutePath(), List.of(), List.of(), policy.hosts()));
		ObjectNode file = json.createObjectNode();
		file.put("format_version", POLICY_BOUND_FORMAT);
		putPaths(file, "write_roots", bound.writeRoots());
		putPaths(file, "write_denials", bound.writeDenials());
		putPaths(file, "read_denials", bound.readDenials());
		putPaths(file, "read_reallowances", bound.readReallowances());
		ArrayNode hosts = file.putArray("hosts");
		for (String host : new TreeSet<>(bound.hosts()))
			hosts.add(host);
		file.put("allow_local_binding", bound.allowLocalBinding());
		Path destination = Files.createDirectories(directory).resolve(POLICY_BOUND_FILE);
		Files.writeString(destination, json.writeValueAsString(file));
		List<String> result = new ArrayList<>(arguments);
		result.add(POLICY_BOUND_OPTION);
		result.add(destination.toString());
		return result;
	}

	/**
	 * Adds an array of paths to a policy bound.
	 *
	 * @param bound the bound
	 * @param field the array's field
	 * @param paths the paths
	 */
	private static void putPaths(ObjectNode bound, String field, List<Path> paths)
	{
		ArrayNode array = bound.putArray(field);
		for (Path path : paths)
			array.add(path.toString());
	}
}
