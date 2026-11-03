package com.holdmyspot.cat.common.updater.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.cli.SandboxExemption;
import com.holdmyspot.cat.common.updater.cli.internal.Arguments.Operation;
import com.holdmyspot.cat.common.updater.engine.HttpsReleaseSource;
import com.holdmyspot.cat.common.updater.engine.UpdateEngine;
import com.holdmyspot.cat.common.updater.engine.UpdateResult;
import com.holdmyspot.cat.common.updater.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.updater.engine.UpdaterState;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import com.holdmyspot.cat.common.updater.trust.TrustedKey;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns one updater process's explicit inputs, output streams and command dispatch.
 */
public final class Invocation
{
	/**
	 * The connection deadline for the fixed policy release source.
	 */
	private static final Duration CONNECT_LIMIT = Duration.ofSeconds(30);
	/**
	 * The deadline for one release download request.
	 */
	private static final Duration REQUEST_LIMIT = Duration.ofMinutes(5);
	private final Path image;
	private final Path cwd;
	private final Platform platform;
	private final Map<String, String> environment;
	private final List<PluginActivation> activations;
	private final List<SandboxExemption> exemptions;
	private final Clock clock;
	private final Waiting waiting;
	private final FileReplacer replacer;
	private final PrintStream stdout;
	private final PrintStream stderr;
	private final JsonMapper mapper = JsonMapper.builder().build();

	/**
	 * Creates one process invocation with explicit boundaries for isolated tests.
	 *
	 * @param image       the actual running jlink image directory
	 * @param cwd         the caller's working directory
	 * @param platform    the running platform
	 * @param environment the boundary-captured environment; routine path authority never comes from this map
	 * @param activations the installed activation services
	 * @param exemptions  the installed permission-syntax services
	 * @param clock       supplies UTC diagnostic times
	 * @param waiting     creates pauses between updater lock attempts
	 * @param replacer    atomically replaces setup files, with platform-appropriate retry waiting
	 * @param stdout      receives exactly one JSON result
	 * @param stderr      receives human diagnostics and setup permission entries
	 */
	public Invocation(Path image, Path cwd, Platform platform, Map<String, String> environment,
		List<PluginActivation> activations, List<SandboxExemption> exemptions, Clock clock, Waiting waiting,
		FileReplacer replacer,
		PrintStream stdout, PrintStream stderr)
	{
		requireThat(image, "image").isNotNull();
		requireThat(cwd, "cwd").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(stdout, "stdout").isNotNull();
		requireThat(stderr, "stderr").isNotNull();
		this.image = image;
		this.cwd = cwd;
		this.platform = platform;
		this.environment = Map.copyOf(environment);
		this.activations = List.copyOf(activations);
		this.exemptions = List.copyOf(exemptions);
		this.clock = clock;
		this.waiting = waiting;
		this.replacer = replacer;
		this.stdout = stdout;
		this.stderr = stderr;
	}

	/**
	 * Runs one complete command, reporting every operational failure as one JSON result.
	 *
	 * @param args the complete argument vector
	 * @return the documented shell exit status
	 */
	public int run(String[] args)
	{
		Set<String> harnesses = activations.stream().map(PluginActivation::harness).collect(Collectors.toSet());
		Arguments arguments;
		try
		{
			arguments = Arguments.parse(args, harnesses);
		}
		catch (IllegalArgumentException e)
		{
			return print(failure(Result.USAGE, "unknown", e.getMessage(), List.of()), null, null);
		}
		try
		{
			return execute(arguments);
		}
		catch (AccessDeniedException e)
		{
			Path denied = image;
			if (e.getFile() != null)
				denied = Path.of(e.getFile());
			if (arguments.operation() == Operation.STATUS)
			{
				return print(failure(Result.REFUSED, selectedHarness(arguments),
					"The updater cannot read " + denied + "; check its read permissions.", List.of(denied)), arguments,
					null);
			}
			return print(failure(Result.NOT_WRITABLE, selectedHarness(arguments),
				"The updater cannot write " + denied + "; add the displayed routine-update exemption.",
				List.of(denied)), arguments, null);
		}
		catch (TrustException e)
		{
			return print(failure(Result.REFUSED, selectedHarness(arguments), e.getMessage(), List.of()), arguments,
				null);
		}
		catch (Exception e)
		{
			Path journal = image.getParent().resolve("state").resolve(selectedHarness(arguments)).
				resolve("activation.json");
			Result result = Result.REFUSED;
			if (Files.exists(journal))
				result = Result.NOT_RESTORED;
			return print(failure(result, selectedHarness(arguments),
				"The updater could not complete the operation: " + e.getMessage(), List.of(journal)), arguments,
				null);
		}
	}

	/**
	 * Selects explicit setup or an operation whose authority is anchored to the running image.
	 *
	 * @param arguments the validated operation
	 * @return its printed result's status
	 * @throws IOException    if installation files cannot be read or replaced
	 * @throws TrustException if setup or policy validation refuses the operation
	 */
	private int execute(Arguments arguments) throws IOException, TrustException
	{
		if (arguments.operation() == Operation.SETUP)
		{
			Locations locations = Locations.forSetup(image, platform, environment);
			Setup setup = new Setup(locations, platform, replacer);
			TrustPolicy policy = setup.setUp(arguments.harness(), arguments.home(), cwd);
			UpdateResult result = new UpdateResult(Result.SET_UP, arguments.harness(),
				Optional.of(setup.version()), Optional.empty(), "The trusted updater is set up.",
				List.of(locations.data(), locations.policy()), true,
				policy.keys().stream().map(TrustedKey::id).toList());
			return print(result, arguments, locations);
		}

		Locations locations = Locations.installed(image);
		Locations.validateCurrent(image);
		if (!Files.isRegularFile(locations.policy()))
		{
			return print(failure(Result.REFUSED, selectedHarness(arguments),
				"Updater policy is missing; run the pinned entry script with --updater-only.",
				List.of(locations.policy())), arguments, locations);
		}
		TrustPolicy policy = TrustPolicy.read(locations.policy());
		if (arguments.harness().isEmpty())
			return statusAll(locations, policy);
		PluginActivation activation = activations.stream().
			filter(provider -> provider.harness().equals(arguments.harness())).findFirst().orElseThrow();
		UpdateResult result;
		try (HttpsReleaseSource source = new HttpsReleaseSource(policy.releaseBaseUrl(), environment,
			CONNECT_LIMIT, REQUEST_LIMIT))
		{
			UpdateEngine engine = new UpdateEngine(locations.data(), policy, source, activation, platform.id(),
				clock, waiting);
			result = switch (arguments.operation())
			{
				case UPDATE -> engine.update(arguments.tag());
				case STATUS -> engine.status();
				case ROLLBACK -> engine.rollback();
				case SETUP -> throw new AssertionError("Setup was already handled");
			};
		}
		return print(result, arguments, locations);
	}

	/**
	 * Reports every configured installation in one read-only JSON result.
	 *
	 * @param locations the installation-bound locations
	 * @param policy    the protected policy
	 * @return zero after printing the status object
	 * @throws IOException    if an installation's state cannot be read
	 * @throws TrustException if the release transport's fixed policy URL is invalid
	 */
	private int statusAll(Locations locations, TrustPolicy policy)
		throws IOException, TrustException
	{
		ObjectNode result = encode(failure(Result.STATUS, "all", "Reported the configured installations.",
			List.of()));
		result.set("key_ids", mapper.valueToTree(policy.keys().stream().map(TrustedKey::id).toList()));
		ArrayNode installations = result.putArray("installations");
		for (PluginActivation activation : activations)
		{
			if (!policy.harnesses().containsKey(activation.harness()))
				continue;
			try (HttpsReleaseSource source = new HttpsReleaseSource(policy.releaseBaseUrl(), environment,
				CONNECT_LIMIT, REQUEST_LIMIT))
			{
				UpdateEngine engine = new UpdateEngine(locations.data(), policy, source, activation, platform.id(),
					clock, waiting);
				installations.add(encode(engine.status()));
			}
		}
		stdout.println(mapper.writeValueAsString(result));
		return 0;
	}

	/**
	 * Writes exactly one result object, adding provider-owned exemptions when the user must configure them.
	 *
	 * @param result    the operation outcome
	 * @param arguments the validated operation, or null for usage
	 * @param locations the resolved locations, or null before resolution
	 * @return the result's shell status
	 */
	private int print(UpdateResult result, Arguments arguments, Locations locations)
	{
		ObjectNode object = encode(result);
		if (result.result() == Result.SET_UP || result.result() == Result.NOT_WRITABLE)
		{
			try
			{
				if (locations == null)
				{
					if (arguments != null && arguments.operation() == Operation.SETUP)
						locations = Locations.forSetup(image, platform, environment);
					else
						locations = Locations.installed(image);
				}
				Path home;
				if (arguments != null && arguments.operation() == Operation.SETUP)
					home = arguments.home();
				else
					home = TrustPolicy.read(locations.policy()).harnesses().get(result.harness());
				if (home != null)
				{
					for (SandboxExemption exemption : exemptions)
					{
						if (!exemption.harness().equals(result.harness()))
							continue;
						Map<String, List<String>> entries = exemption.entries(locations.launcher(platform), home);
						object.set("exemption", mapper.valueToTree(entries));
						for (Map.Entry<String, List<String>> entry : entries.entrySet())
						{
							for (String value : entry.getValue())
								stderr.println(entry.getKey() + ": " + value);
						}
					}
				}
			}
			catch (IOException | TrustException e)
			{
				stderr.println("Could not read setup permission entries: " + e.getMessage());
			}
		}
		if (result.result().exitStatus() != 0)
		{
			stderr.println(result.message());
			appendDiagnostic(arguments, locations, result.message());
		}
		stdout.println(mapper.writeValueAsString(object));
		return result.result().exitStatus();
	}

	/**
	 * Converts an engine outcome to the updater's snake_case output contract.
	 *
	 * @param result the outcome
	 * @return the JSON object
	 */
	private ObjectNode encode(UpdateResult result)
	{
		ObjectNode object = mapper.createObjectNode();
		object.put("result", result.result().name().toLowerCase(Locale.ROOT));
		object.put("harness", result.harness());
		object.put("version", result.version().orElse(""));
		result.previousVersion().ifPresent(version -> object.put("previous_version", version));
		object.put("message", result.message());
		ArrayNode paths = object.putArray("paths");
		for (Path path : result.paths())
			paths.add(path.toString());
		object.put("consistent", result.consistent());
		object.set("key_ids", mapper.valueToTree(result.keyIds()));
		return object;
	}

	/**
	 * Appends a diagnostic only where a mutating operation already has its protected state directory.
	 * Status remains read-only, including its failure paths.
	 *
	 * @param arguments the validated command, or null for usage
	 * @param locations the resolved protected locations, or null before resolution
	 * @param message   the diagnostic
	 */
	private void appendDiagnostic(Arguments arguments, Locations locations, String message)
	{
		if (arguments == null || arguments.operation() == Operation.STATUS)
			return;
		Path data = image.getParent();
		if (locations != null)
			data = locations.data();
		Path log = UpdaterState.of(data, selectedHarness(arguments)).log();
		if (!Files.isDirectory(log.getParent()))
			return;
		try
		{
			Files.writeString(log,
				clock.instant() + " " + message.replace('\n', ' ').replace('\r', ' ') + "\n", UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (IOException e)
		{
			stderr.println("Could not append updater diagnostic: " + e.getMessage());
		}
	}

	/**
	 * Creates a complete non-activation outcome.
	 *
	 * @param result  the outcome and status
	 * @param harness the selected provider or a diagnostic scope
	 * @param message the actionable diagnostic
	 * @param paths   affected paths
	 * @return the outcome without recorded versions
	 */
	private static UpdateResult failure(Result result, String harness, String message, List<Path> paths)
	{
		return new UpdateResult(result, harness, Optional.empty(), Optional.empty(), message, paths,
			result != Result.NOT_RESTORED,
			List.of());
	}

	/**
	 * Returns the selected provider or the all-installations scope for diagnostics.
	 *
	 * @param arguments the validated operation
	 * @return the provider or all-installations scope
	 */
	private static String selectedHarness(Arguments arguments)
	{
		if (arguments.harness().isEmpty())
			return "all";
		return arguments.harness();
	}
}
