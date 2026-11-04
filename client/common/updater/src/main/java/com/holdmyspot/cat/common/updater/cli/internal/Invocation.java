package com.holdmyspot.cat.common.updater.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.updater.activation.HarnessCommand;
import com.holdmyspot.cat.common.updater.activation.HarnessIntegration;
import com.holdmyspot.cat.common.updater.activation.PluginActivation;
import com.holdmyspot.cat.common.updater.cli.internal.Arguments.Operation;
import com.holdmyspot.cat.common.updater.engine.HttpsReleaseSource;
import com.holdmyspot.cat.common.updater.engine.Registration;
import com.holdmyspot.cat.common.updater.engine.Uninstaller;
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
 * Owns one updater process's explicit inputs, output streams and command dispatch. A routine operation acts only on
 * the harness whose installation directory contains the running updater.
 */
public final class Invocation
{
	/**
	 * The exit status with which a completed uninstall hands its saved result to the stable launcher, which deletes
	 * the installation's data directory after the updater exits and then prints the result.
	 */
	public static final int UNINSTALL_HANDOVER_STATUS = 79;
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
	private final List<HarnessIntegration> integrations;
	private final HarnessCliFactory cliFactory;
	private final Clock clock;
	private final Waiting waiting;
	private final FileReplacer replacer;
	private final PrintStream stdout;
	private final PrintStream stderr;
	private final JsonMapper mapper = JsonMapper.builder().build();

	/**
	 * Creates one process invocation with explicit boundaries for isolated tests.
	 *
	 * @param image        the running updater version directory
	 * @param cwd          the caller's working directory
	 * @param platform     the running platform
	 * @param environment  the boundary-captured environment; routine path authority never comes from this map
	 * @param activations  the installed activation services
	 * @param integrations the installed harness integration services
	 * @param cliFactory   runs the recorded harness program
	 * @param clock        supplies UTC diagnostic times
	 * @param waiting      creates pauses between updater lock attempts
	 * @param replacer     atomically replaces setup files, with platform-appropriate retry waiting
	 * @param stdout       receives exactly one JSON result
	 * @param stderr       receives human diagnostics
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Invocation(Path image, Path cwd, Platform platform, Map<String, String> environment,
		List<PluginActivation> activations, List<HarnessIntegration> integrations, HarnessCliFactory cliFactory,
		Clock clock, Waiting waiting, FileReplacer replacer, PrintStream stdout, PrintStream stderr)
	{
		requireThat(image, "image").isNotNull();
		requireThat(cwd, "cwd").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(cliFactory, "cliFactory").isNotNull();
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
		this.integrations = List.copyOf(integrations);
		this.cliFactory = cliFactory;
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
				"The updater cannot write " + denied + "; check its permissions.", List.of(denied)), arguments, null);
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
			return setUp(arguments);

		Locations locations = Locations.installed(image);
		Locations.validateCurrent(image);
		String own = locations.harness();
		if (!arguments.harness().isEmpty() && !arguments.harness().equals(own))
		{
			return print(failure(Result.REFUSED, own, "This installation manages " + own + " only; use the updater " +
				"of the " + arguments.harness() + " installation for " + arguments.harness() + ".", List.of()),
				arguments, locations);
		}
		if (!Files.isRegularFile(locations.policy()))
		{
			return print(failure(Result.REFUSED, own, "Updater policy is missing; " +
				Locations.rerunEntryCommand(own) + ".", List.of(locations.policy())), arguments, locations);
		}
		TrustPolicy policy = TrustPolicy.read(locations.policy());
		if (!policy.harnesses().keySet().equals(Set.of(own)))
		{
			return print(failure(Result.REFUSED, own, "The policy " + locations.policy() + " records the harnesses " +
				policy.harnesses().keySet() + ", but this installation manages " + own + " only; " +
				Locations.rerunEntryCommand(own) + ".", List.of(locations.policy())), arguments, locations);
		}
		Optional<HarnessCommand> command = policy.command(own);
		if (command.isEmpty())
		{
			return print(failure(Result.REFUSED, own, "The policy was written by an earlier release and does not " +
				"record the harness program; " + Locations.rerunEntryCommand(own) + ".", List.of(locations.policy())),
				arguments, locations);
		}
		Optional<PluginActivation> activation = activations.stream().
			filter(provider -> provider.harness().equals(own)).findFirst();
		if (activation.isEmpty())
		{
			return print(failure(Result.REFUSED, own, "This updater has no plugin provider for " + own + ".",
				List.of()), arguments, locations);
		}
		if (arguments.operation() == Operation.STATUS || arguments.operation() == Operation.ROLLBACK)
		{
			try (HttpsReleaseSource source = new HttpsReleaseSource(policy.releaseBaseUrl(), environment,
				CONNECT_LIMIT, REQUEST_LIMIT))
			{
				UpdateEngine engine = new UpdateEngine(locations.data(), policy, source, activation.get(),
					platform.id(), clock, waiting);
				if (arguments.operation() == Operation.ROLLBACK)
					return print(engine.rollback(), arguments, locations);
				UpdateResult status = engine.status();
				if (arguments.harness().isEmpty())
					return statusAll(status, policy);
				return print(status, arguments, locations);
			}
		}
		Optional<HarnessIntegration> integration = integrations.stream().
			filter(provider -> provider.harness().equals(own)).findFirst();
		if (integration.isEmpty())
		{
			return print(failure(Result.REFUSED, own, "This updater has no harness integration for " + own + ".",
				List.of()), arguments, locations);
		}
		Path program = Path.of(command.get().command().getFirst());
		if (!Files.isRegularFile(program) || !Files.isExecutable(program))
		{
			return print(failure(Result.REFUSED, own, "The " + own + " program " + program + " that setup recorded " +
				"no longer exists or is not executable; " + Locations.rerunEntryCommand(own) + ".", List.of(program)),
				arguments, locations);
		}
		Path home = policy.harnesses().get(own);
		Registration registration = new Registration(integration.get(), cliFactory.create(command.get(), home));
		if (arguments.operation() == Operation.UNINSTALL)
		{
			UpdateResult result = new Uninstaller(locations.data(), locations.policy(), policy, activation.get(),
				registration, clock, waiting).uninstall(arguments.deleteData());
			if (result.result() != Result.UNINSTALLED)
				return print(result, arguments, locations);
			return handOver(result, locations);
		}
		try (HttpsReleaseSource source = new HttpsReleaseSource(policy.releaseBaseUrl(), environment,
			CONNECT_LIMIT, REQUEST_LIMIT))
		{
			UpdateEngine engine = new UpdateEngine(locations.data(), policy, source, activation.get(), platform.id(),
				clock, waiting);
			UpdateResult result = switch (arguments.operation())
			{
				case INSTALL -> engine.install(arguments.tag(), registration);
				case UPDATE -> engine.update(arguments.tag(), registration);
				case SETUP, STATUS, ROLLBACK, UNINSTALL -> throw new AssertionError(arguments.operation());
			};
			return print(result, arguments, locations);
		}
	}

	/**
	 * Sets a harness's installation up from the extracted updater archive that the running updater belongs to.
	 *
	 * @param arguments the validated setup operation
	 * @return its printed result's status
	 * @throws IOException    if installation files cannot be read or replaced
	 * @throws TrustException if setup refuses
	 */
	private int setUp(Arguments arguments) throws IOException, TrustException
	{
		String harness = arguments.harness();
		Locations locations = Locations.forSetup(image, platform, environment, harness);
		PluginActivation activation = activations.stream().filter(provider -> provider.harness().equals(harness)).
			findFirst().orElseThrow();
		Optional<HarnessIntegration> integration = integrations.stream().
			filter(provider -> provider.harness().equals(harness)).findFirst();
		if (integration.isEmpty())
		{
			return print(failure(Result.REFUSED, harness, "This updater has no harness integration for " + harness +
				".", List.of()), arguments, null);
		}
		Setup setup = new Setup(locations, platform, replacer, DirectoryMover.ATOMIC, cliFactory, clock, waiting);
		Setup.Outcome outcome;
		try (HttpsReleaseSource source = new HttpsReleaseSource(setup.defaults().releaseBaseUrl(), environment,
			CONNECT_LIMIT, REQUEST_LIMIT))
		{
			outcome = setup.setUp(harness, arguments.home(), arguments.plugin(), cwd, environment, activation,
				integration.get(), source);
		}
		UpdateResult plugin = outcome.plugin();
		if (plugin.result().exitStatus() != 0)
		{
			return print(new UpdateResult(plugin.result(), harness, plugin.version(), plugin.previousVersion(),
				"The updater " + outcome.version() + " is installed, but the plugin step failed: " + plugin.message() +
				" Run the entry command again to repair the installation.", plugin.paths(), plugin.consistent(),
				plugin.keyIds()), arguments, locations);
		}
		ObjectNode object = encode(new UpdateResult(Result.SET_UP, harness, Optional.of(outcome.version()),
			Optional.empty(), "CAT is set up for " + harness + ".", List.of(locations.data(), locations.policy()),
			true, outcome.policy().keys().stream().map(TrustedKey::id).toList()));
		object.put("plugin_version", plugin.version().orElse(""));
		object.put("launcher", outcome.launcher().toString());
		ObjectNode permissions = object.putObject("permissions");
		for (Map.Entry<String, List<String>> entry : outcome.permissions().entrySet())
		{
			ArrayNode entries = permissions.putArray(entry.getKey());
			entry.getValue().forEach(entries::add);
		}
		stdout.println(mapper.writeValueAsString(object));
		return 0;
	}

	/**
	 * Reports the installation's harness in the result shape that lists every installation.
	 *
	 * @param status the harness's status
	 * @param policy the protected policy
	 * @return zero after printing the status object
	 */
	private int statusAll(UpdateResult status, TrustPolicy policy)
	{
		ObjectNode result = encode(failure(Result.STATUS, "all", "Reported the configured installations.",
			List.of()));
		result.set("key_ids", mapper.valueToTree(policy.keys().stream().map(TrustedKey::id).toList()));
		result.putArray("installations").add(encode(status));
		stdout.println(mapper.writeValueAsString(result));
		return 0;
	}

	/**
	 * Saves a completed uninstall's result for the stable launcher and exits without printing it, because the launcher
	 * deletes the data directory first and prints the result only when that succeeded.
	 *
	 * @param result    the completed uninstall
	 * @param locations the installation's locations
	 * @return the hand-over status
	 * @throws IOException if the result cannot be saved
	 */
	private int handOver(UpdateResult result, Locations locations) throws IOException
	{
		Path file = locations.data().resolve("state/uninstall-result.json");
		Files.createDirectories(file.getParent());
		Path temporary = Files.createTempFile(file.getParent(), ".uninstall-result-", ".tmp");
		try
		{
			Files.writeString(temporary, mapper.writeValueAsString(encode(result)) + "\n", UTF_8);
			replacer.replace(temporary, file);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
		return UNINSTALL_HANDOVER_STATUS;
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
		if (result.result() == Result.STATUS)
			object.put("installed", result.version().isPresent());
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
	 * Returns the harness that an operation acts on, for diagnostics.
	 *
	 * @param arguments the validated operation
	 * @return the named harness, or else the harness of the installation that contains the running updater
	 */
	private String selectedHarness(Arguments arguments)
	{
		if (!arguments.harness().isEmpty())
			return arguments.harness();
		Path data = image.getParent();
		if (data == null || data.getFileName() == null)
			return "unknown";
		return data.getFileName().toString();
	}
}
