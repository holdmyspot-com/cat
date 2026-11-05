package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installation.CapacityLocks;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.installer.cli.internal.Arguments.Operation;
import com.holdmyspot.cat.common.installer.engine.Consent;
import com.holdmyspot.cat.common.installer.engine.FolderRetention;
import com.holdmyspot.cat.common.installer.engine.HttpsReleaseSource;
import com.holdmyspot.cat.common.installer.engine.HarnessSession;
import com.holdmyspot.cat.common.installer.engine.InstallCompletion;
import com.holdmyspot.cat.common.installer.engine.InstallRequest;
import com.holdmyspot.cat.common.installer.engine.UninstallResult;
import com.holdmyspot.cat.common.installer.engine.Uninstaller;
import com.holdmyspot.cat.common.installer.engine.UpdateEngine;
import com.holdmyspot.cat.common.installer.engine.UpdateResult;
import com.holdmyspot.cat.common.installer.engine.UpdateResult.Result;
import com.holdmyspot.cat.common.installer.engine.InstallerState;
import com.holdmyspot.cat.common.installer.engine.ProcessElevation;
import com.holdmyspot.cat.common.installer.engine.ProcessMigrationRunner;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.TrustedKey;
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
 * Owns one installer process's explicit inputs, output streams and command dispatch. An installer that runs from a
 * version folder acts only on the harness whose version folders contain it.
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
	private final Path userHome;
	private final Platform platform;
	private final Map<String, String> environment;
	private final List<PluginActivation> activations;
	private final List<HarnessIntegration> integrations;
	private final HarnessCliFactory cliFactory;
	private final Clock clock;
	private final Waiting waiting;
	private final FileReplacer replacer;
	private final Terminal terminal;
	private final ProcessStarter starter;
	private final PrintStream stdout;
	private final PrintStream stderr;
	private final JsonMapper mapper = JsonMapper.builder().build();
	/**
	 * The plugin data folder of the installation that the running installer belongs to, once it is known.
	 */
	private Path resolvedData;
	/**
	 * The harness of the installation that the running installer belongs to, once it is known.
	 */
	private String resolvedHarness;

	/**
	 * Creates one process invocation with explicit boundaries for isolated tests.
	 *
	 * @param image        the plugin folder that the running installer runs from: the extracted plugin archive for
	 *                     install, otherwise the version folder of the installed plugin
	 * @param cwd          the caller's working directory
	 * @param userHome     the user's home directory, which holds the capacity locks and in which the harness homes lie
	 *                     unless the environment names them
	 * @param platform     the running platform
	 * @param environment  the boundary-captured environment; routine path authority never comes from this map
	 * @param activations  the installed activation services
	 * @param integrations the installed harness integration services
	 * @param cliFactory   runs the recorded harness program
	 * @param clock        supplies UTC diagnostic times
	 * @param waiting      creates pauses between installer lock attempts
	 * @param replacer     atomically replaces installed files, with platform-appropriate retry waiting
	 * @param terminal     asks the user whether they allow a change that needs their permission
	 * @param starter      starts the program that deletes, once the installer's process has exited, the folders that
	 *                     it cannot delete while it runs
	 * @param stdout       receives exactly one JSON result
	 * @param stderr       receives human diagnostics
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Invocation(Path image, Path cwd, Path userHome, Platform platform, Map<String, String> environment,
		List<PluginActivation> activations, List<HarnessIntegration> integrations, HarnessCliFactory cliFactory,
		Clock clock, Waiting waiting, FileReplacer replacer, Terminal terminal, ProcessStarter starter,
		PrintStream stdout, PrintStream stderr)
	{
		requireThat(image, "image").isNotNull();
		requireThat(cwd, "cwd").isNotNull();
		requireThat(userHome, "userHome").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(cliFactory, "cliFactory").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(terminal, "terminal").isNotNull();
		requireThat(starter, "starter").isNotNull();
		requireThat(stdout, "stdout").isNotNull();
		requireThat(stderr, "stderr").isNotNull();
		this.image = image;
		this.cwd = cwd;
		this.userHome = userHome;
		this.platform = platform;
		this.environment = Map.copyOf(environment);
		this.activations = List.copyOf(activations);
		this.integrations = List.copyOf(integrations);
		this.cliFactory = cliFactory;
		this.clock = clock;
		this.waiting = waiting;
		this.replacer = replacer;
		this.terminal = terminal;
		this.starter = starter;
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
					"The installer cannot read " + denied + "; check its read permissions.", List.of(denied)), arguments,
					null);
			}
			return print(failure(Result.NOT_WRITABLE, selectedHarness(arguments),
				"The installer cannot write " + denied + "; check its permissions.", List.of(denied)), arguments, null);
		}
		catch (TrustException e)
		{
			return print(failure(Result.REFUSED, selectedHarness(arguments), e.getMessage(), List.of()), arguments,
				null);
		}
		catch (Exception e)
		{
			return print(unexpectedFailure(arguments, e), arguments, null);
		}
	}

	/**
	 * Describes an exception that no operation step anticipated. {@code status}, which never writes, is refused, and
	 * every other operation may have changed installed state before it stopped, which its next run repairs.
	 *
	 * @param arguments the validated operation
	 * @param cause     the exception
	 * @return the failure result, listing only the recovery files that exist
	 */
	private UpdateResult unexpectedFailure(Arguments arguments, Exception cause)
	{
		String harness = selectedHarness(arguments);
		String operation = arguments.operation().name().toLowerCase(Locale.ROOT);
		String message = "The installer could not complete " + operation + ": " + cause.getMessage();
		List<Path> journals = List.of();
		if (resolvedData != null)
			journals = List.of(resolvedData.resolve("state/uninstall.json")).stream().filter(Files::exists).toList();
		return switch (arguments.operation())
		{
			case STATUS -> failure(Result.REFUSED, harness, message, journals);
			case INSTALL -> failure(Result.FAILED, harness, message + "; " + Locations.rerunEntryCommand(harness) +
				" to continue.", journals);
			case UNINSTALL -> failure(Result.FAILED, harness, message + "; run the same " + operation +
				" command again to continue.", journals);
		};
	}

	/**
	 * Selects install, or an operation on the installation whose version folder the running installer runs from.
	 *
	 * @param arguments the validated operation
	 * @return its printed result's status
	 * @throws IOException    if installation files cannot be read or replaced
	 * @throws TrustException if install, the running installer's location or policy validation refuses the operation
	 */
	private int execute(Arguments arguments) throws IOException, TrustException
	{
		if (arguments.operation() == Operation.INSTALL)
			return install(arguments);

		Locations locations = Locations.installed(image, integrations);
		resolvedData = locations.data();
		resolvedHarness = locations.harness();
		String own = locations.harness();
		if (!arguments.harness().isEmpty() && !arguments.harness().equals(own))
			return print(otherHarness(own, arguments.harness()), arguments, locations);
		Managed managed = manage(own, locations.policy(), arguments.operation() == Operation.STATUS,
			arguments.operation() != Operation.UNINSTALL);
		if (managed.refusal() != null)
			return print(managed.refusal(), arguments, locations);
		if (arguments.operation() == Operation.STATUS)
		{
			try (HttpsReleaseSource source = new HttpsReleaseSource(managed.policy().releaseBaseUrl(), environment,
				CONNECT_LIMIT, REQUEST_LIMIT))
			{
				UpdateEngine engine = new UpdateEngine(locations.data(), managed.policy(), source, managed.activation(),
					platform.id(), clock, waiting);
				return print(engine.status(), arguments, locations);
			}
		}
		HarnessSession session = managed.session();
		UninstallResult result = new Uninstaller(locations.data(), managed.policy(), managed.activation(), session,
			locations.image(), clock, waiting).uninstall(arguments.deleteData());
		return printUninstall(result, arguments, locations);
	}

	/**
	 * Installs a harness's plugin: through the installation whose version folder the running installer runs from, or
	 * from the extracted plugin archive that the running installer belongs to.
	 *
	 * @param arguments the validated install operation
	 * @return its printed result's status
	 * @throws IOException    if installation files cannot be read or replaced
	 * @throws TrustException if install refuses
	 */
	private int install(Arguments arguments) throws IOException, TrustException
	{
		Locations locations;
		try
		{
			locations = Locations.installed(image, integrations);
		}
		catch (TrustException _)
		{
			return installArchive(arguments);
		}
		resolvedData = locations.data();
		resolvedHarness = locations.harness();
		String own = locations.harness();
		if (!arguments.harness().equals(own))
			return print(otherHarness(own, arguments.harness()), arguments, locations);
		Managed managed = manage(own, locations.policy(), false, true);
		if (managed.refusal() != null)
			return print(managed.refusal(), arguments, locations);
		Optional<UpdateResult> otherHome = refuseOtherHome(own, arguments.home(), managed.home());
		if (otherHome.isPresent())
			return print(otherHome.get(), arguments, locations);
		try (HttpsReleaseSource source = new HttpsReleaseSource(managed.policy().releaseBaseUrl(), environment,
			CONNECT_LIMIT, REQUEST_LIMIT))
		{
			UpdateEngine engine = new UpdateEngine(locations.data(), managed.policy(), source, managed.activation(),
				platform.id(), clock, waiting);
			UserConsent consent = new UserConsent(arguments.createProfile());
			UpdateResult result = engine.install(arguments.tag(), request(managed, locations.image(), consent));
			if (result.result() == Result.REFUSED && consent.withheld())
				return print(result, withheld(result, consent, arguments, locations.image()), arguments, locations);
			return print(result, arguments, locations);
		}
	}

	/**
	 * Installs a harness's plugin from the extracted plugin archive that the running installer belongs to. Where the
	 * harness already has a CAT version and a valid policy of this installation, the archive's version is installed
	 * under the policy like a signed release; otherwise install writes the policy and installs the archive's version.
	 *
	 * @param arguments the validated install operation
	 * @return its printed result's status
	 * @throws IOException    if installation files cannot be read or replaced
	 * @throws TrustException if install refuses
	 */
	private int installArchive(Arguments arguments) throws IOException, TrustException
	{
		String harness = arguments.harness();
		if (arguments.tag() != null)
		{
			return print(failure(Result.REFUSED, harness, "The extracted plugin archive installs only its own " +
				"version; run the installer in the installed version folder to install release " + arguments.tag() +
				".", List.of()), arguments, null);
		}
		Optional<HarnessIntegration> integration = integrations.stream().
			filter(provider -> provider.harness().equals(harness)).findFirst();
		if (integration.isEmpty())
		{
			return print(failure(Result.REFUSED, harness, "This installer has no harness integration for " + harness +
				".", List.of()), arguments, null);
		}
		PluginActivation activation = activations.stream().filter(provider -> provider.harness().equals(harness)).
			findFirst().orElseThrow();
		Path home = arguments.home();
		if (home == null)
			home = integration.get().installation(environment, userHome).home();
		if (home.isAbsolute() && Files.isDirectory(home))
		{
			Path real = home.toRealPath();
			HarnessInstallation installation = integration.get().installation(real);
			if (Files.isRegularFile(installation.policy(), NOFOLLOW_LINKS) &&
				activation.installed(real).version().isPresent())
			{
				Managed managed = manage(harness, installation.policy(), false, true);
				if (managed.refusal() == null && refuseOtherHome(harness, real, managed.home()).isEmpty())
				{
					resolvedData = installation.dataDirectory();
					resolvedHarness = harness;
					try (HttpsReleaseSource source = new HttpsReleaseSource(managed.policy().releaseBaseUrl(),
						environment, CONNECT_LIMIT, REQUEST_LIMIT))
					{
						UpdateEngine engine = new UpdateEngine(installation.dataDirectory(), managed.policy(), source,
							activation, platform.id(), clock, waiting);
						UserConsent consent = new UserConsent(arguments.createProfile());
						UpdateResult result = engine.installFrom(image, request(managed, image, consent));
						if (result.result() == Result.REFUSED && consent.withheld())
							return print(result, withheld(result, consent, arguments, null), arguments, null);
						return print(result, arguments, null);
					}
				}
			}
		}
		return installFirst(arguments, activation, integration.get());
	}

	/**
	 * Returns the refusal of an operation on a harness other than the one whose version folders contain the running
	 * installer.
	 *
	 * @param own     the harness of the running installer
	 * @param named   the harness that the operation names
	 * @return the refusal
	 */
	private static UpdateResult otherHarness(String own, String named)
	{
		return failure(Result.REFUSED, own, "This installation manages " + own + " only; use the installer of the " +
			named + " installation for " + named + ".", List.of());
	}

	/**
	 * Refuses a harness home other than the one that the policy records.
	 *
	 * @param harness  the harness
	 * @param named    the harness home that install names, or null when it names none
	 * @param recorded the harness home that the policy records
	 * @return the refusal, or empty when install names no other home
	 * @throws IOException if a home cannot be resolved
	 */
	private static Optional<UpdateResult> refuseOtherHome(String harness, Path named, Path recorded)
		throws IOException
	{
		if (named == null)
			return Optional.empty();
		Path real = Locations.resolveExistingAncestor(named.toAbsolutePath().normalize());
		if (real.equals(Locations.resolveExistingAncestor(recorded)))
			return Optional.empty();
		return Optional.of(failure(Result.REFUSED, harness, "This installation manages the " + harness + " home " +
			recorded + " only, not " + named + "; install CAT into " + named + " with the CAT entry command.",
			List.of()));
	}

	/**
	 * Reads and checks the policy of a harness's installation and selects the harness's providers.
	 *
	 * @param own           the harness
	 * @param policyFile    the installation's policy
	 * @param readOnly      true when the operation only reads the installation, and needs no harness integration
	 * @param needsProgram  true when the operation runs the harness program that install recorded
	 * @return the checked installation, or its refusal
	 * @throws IOException    if the policy cannot be read
	 * @throws TrustException if the policy is invalid
	 */
	private Managed manage(String own, Path policyFile, boolean readOnly, boolean needsProgram)
		throws IOException, TrustException
	{
		if (!Files.isRegularFile(policyFile))
		{
			return Managed.refused(failure(Result.REFUSED, own, "Installer policy is missing; " +
				Locations.rerunEntryCommand(own) + ".", List.of(policyFile)));
		}
		TrustPolicy policy = TrustPolicy.read(policyFile);
		if (!policy.harnesses().keySet().equals(Set.of(own)))
		{
			return Managed.refused(failure(Result.REFUSED, own, "The policy " + policyFile + " records the " +
				"harnesses " + policy.harnesses().keySet() + ", but this installation manages " + own + " only; " +
				Locations.rerunEntryCommand(own) + ".", List.of(policyFile)));
		}
		Optional<HarnessCommand> command = policy.command(own);
		if (command.isEmpty())
		{
			return Managed.refused(failure(Result.REFUSED, own, "The policy was written by an earlier release and " +
				"does not record the harness program; " + Locations.rerunEntryCommand(own) + ".", List.of(policyFile)));
		}
		Optional<PluginActivation> activation = activations.stream().
			filter(provider -> provider.harness().equals(own)).findFirst();
		if (activation.isEmpty())
		{
			return Managed.refused(failure(Result.REFUSED, own, "This installer has no plugin provider for " + own +
				".", List.of()));
		}
		Path home = policy.harnesses().get(own);
		if (readOnly)
			return new Managed(policy, activation.get(), home, null, null);
		Optional<HarnessIntegration> integration = integrations.stream().
			filter(provider -> provider.harness().equals(own)).findFirst();
		if (integration.isEmpty())
		{
			return Managed.refused(failure(Result.REFUSED, own, "This installer has no harness integration for " +
				own + ".", List.of()));
		}
		// Uninstall needs the program only to unregister the plugin, and skips that step when the program is gone
		Path program = Path.of(command.get().command().getFirst());
		if (needsProgram && (!Files.isRegularFile(program) || !Files.isExecutable(program)))
		{
			return Managed.refused(failure(Result.REFUSED, own, "The " + own + " program " + program + " that install " +
				"recorded no longer exists or is not executable; " + Locations.rerunEntryCommand(own) + ".",
				List.of(program)));
		}
		HarnessSession session = new HarnessSession(integration.get(), cliFactory.create(command.get(), home),
			replacer);
		return new Managed(policy, activation.get(), home, session, null);
	}

	/**
	 * Returns the request of an install that the running installer performs.
	 *
	 * @param managed the checked installation
	 * @param plugin  the plugin folder that the running installer runs from
	 * @param consent decides whether the user allows a change that needs their permission
	 * @return the request
	 */
	private InstallRequest request(Managed managed, Path plugin, Consent consent)
	{
		String harness = managed.activation().harness();
		List<HarnessInstallation> others = integrations.stream().
			filter(provider -> !provider.harness().equals(harness)).
			map(provider -> provider.installation(environment, userHome)).toList();
		return new InstallRequest(managed.session(), plugin, others, CapacityLocks.root(userHome), consent);
	}

	/**
	 * Installs a harness's plugin from the extracted plugin archive that the running installer belongs to and writes
	 * the installation's policy, then completes the install as every later install does.
	 *
	 * @param arguments   the validated install operation
	 * @param activation  the harness's activation provider
	 * @param integration the harness's integration
	 * @return its printed result's status
	 * @throws IOException    if installation files cannot be read or replaced
	 * @throws TrustException if install refuses
	 */
	private int installFirst(Arguments arguments, PluginActivation activation, HarnessIntegration integration)
		throws IOException, TrustException
	{
		String harness = arguments.harness();
		List<HarnessIntegration> others = integrations.stream().
			filter(provider -> !provider.harness().equals(harness)).toList();
		UserConsent consent = new UserConsent(arguments.createProfile());
		Install.Outcome outcome;
		try
		{
			outcome = new Install(image, platform, replacer, cliFactory, ProcessElevation::isElevated,
				new ProcessMigrationRunner()).
				install(harness, arguments.home(), cwd, environment, userHome, activation, integration, others, consent);
		}
		catch (TrustException e)
		{
			if (!consent.withheld())
				throw e;
			UpdateResult refusal = failure(Result.REFUSED, harness, e.getMessage(), List.of());
			return print(refusal, withheld(refusal, consent, arguments, null), arguments, null);
		}
		HarnessInstallation installation = integration.installation(outcome.policy().harnesses().get(harness));
		resolvedData = installation.dataDirectory();
		resolvedHarness = harness;
		FolderRetention retention = new InstallCompletion(integration, installation).completeLocked(outcome.version(),
			outcome.before(), image, clock, waiting);
		UpdateResult result = new UpdateResult(Result.INSTALLED, harness, Optional.of(outcome.version().release()),
			Optional.empty(), retention.append(outcome.entries().withNotices("CAT is installed for " + harness + ".")),
			List.of(outcome.versionFolder(), installation.policy()), true,
			outcome.policy().keys().stream().map(TrustedKey::id).toList()).
			withExitDeletions(retention.exitDeletions());
		ObjectNode object = encode(result);
		object.put("plugin_version", outcome.version().toString());
		object.put("aot_cache", outcome.startupCache());
		object.put("launcher", outcome.installer().toString());
		ObjectNode permissions = object.putObject("permissions");
		for (Map.Entry<String, List<String>> entry : outcome.entries().recorded().entrySet())
		{
			ArrayNode entries = permissions.putArray(entry.getKey());
			entry.getValue().forEach(entries::add);
		}
		return print(result, object, arguments, null);
	}

	/**
	 * Encodes the refusal of an install whose change the user did not allow, saying how to allow it.
	 *
	 * @param refusal   the refusal, whose message is the question that install asked
	 * @param consent   the user's answer
	 * @param arguments the validated install operation
	 * @param plugin    the version folder that the running installer runs from, or null when it runs from the
	 *                  extracted plugin archive, which only the entry command runs
	 * @return the encoded refusal
	 */
	private ObjectNode withheld(UpdateResult refusal, UserConsent consent, Arguments arguments, Path plugin)
	{
		String command = null;
		String answer;
		if (consent.declined())
			answer = "You answered no, so CAT changed nothing.";
		else if (plugin == null)
		{
			answer = "CAT changed nothing. To allow it, open a terminal and " +
				Locations.rerunEntryCommand(refusal.harness()) + "; install then asks for your permission.";
		}
		else
		{
			command = ConsentCommand.of(plugin, platform, arguments);
			answer = "CAT changed nothing. To allow it, run: " + command;
		}
		ObjectNode object = encode(new UpdateResult(refusal.result(), refusal.harness(), refusal.version(),
			refusal.previousVersion(), refusal.message() + " " + answer, refusal.paths(), refusal.consistent(),
			refusal.keyIds()));
		if (command != null)
			object.put("consent_command", command);
		return object;
	}

	/**
	 * Prints an uninstall's result, with whether the plugin was unregistered through the harness program when the
	 * uninstall completed.
	 *
	 * @param result    the uninstall's outcome
	 * @param arguments the validated operation
	 * @param locations the installation's locations
	 * @return the printed result's status
	 */
	private int printUninstall(UninstallResult result, Arguments arguments, Locations locations)
	{
		ObjectNode object = encode(result.outcome());
		if (result.outcome().result() == Result.UNINSTALLED)
			object.put("unregistered", result.unregistered());
		return print(result.outcome(), object, arguments, locations);
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
		return print(result, encode(result), arguments, locations);
	}

	/**
	 * Writes exactly one encoded result object, then deletes the folders that the outcome leaves for deletion once the
	 * installer no longer needs its own files.
	 *
	 * @param result    the operation outcome
	 * @param object    the outcome's encoding
	 * @param arguments the validated operation, or null for usage
	 * @param locations the resolved locations, or null before resolution
	 * @return the result's shell status
	 */
	private int print(UpdateResult result, ObjectNode object, Arguments arguments, Locations locations)
	{
		if (result.result().exitStatus() != 0)
		{
			stderr.println(result.message());
			appendDiagnostic(arguments, locations, result.message());
		}
		stdout.println(mapper.writeValueAsString(object));
		stdout.flush();
		new ExitDeletion(platform, environment, starter, stderr).delete(result.exitDeletions());
		return result.result().exitStatus();
	}

	/**
	 * Converts an engine outcome to the installer's snake_case output contract.
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
		result.aotCache().ifPresent(written -> object.put("aot_cache", written));
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
		Path data = resolvedData;
		if (locations != null)
			data = locations.data();
		if (data == null)
			return;
		Path log = InstallerState.of(data, selectedHarness(arguments)).log();
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
			stderr.println("Could not append installer diagnostic: " + e.getMessage());
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
			result != Result.FAILED, List.of());
	}

	/**
	 * Returns the harness that an operation acts on, for diagnostics.
	 *
	 * @param arguments the validated operation
	 * @return the named harness, or else the harness of the installation that the running installer belongs to, or
	 *         {@code unknown} before it is known
	 */
	private String selectedHarness(Arguments arguments)
	{
		if (!arguments.harness().isEmpty())
			return arguments.harness();
		if (resolvedHarness != null)
			return resolvedHarness;
		return "unknown";
	}

	/**
	 * The user's permission for a change that install needs, given by the command line or asked in the terminal.
	 */
	private final class UserConsent implements Consent
	{
		private final boolean given;
		private boolean declined;
		private boolean unasked;

		/**
		 * Creates the user's permission.
		 *
		 * @param given true if the command line gives it
		 */
		UserConsent(boolean given)
		{
			this.given = given;
		}

		@Override
		public boolean grants(String request) throws IOException
		{
			requireThat(request, "request").isNotNull();
			if (given)
				return true;
			Optional<String> answer = terminal.ask(request + " Allow this change? [y/N] ");
			if (answer.isEmpty())
			{
				unasked = true;
				return false;
			}
			String reply = answer.get().strip().toLowerCase(Locale.ROOT);
			if (reply.equals("y") || reply.equals("yes"))
				return true;
			declined = true;
			return false;
		}

		/**
		 * Returns whether the user withheld a permission that install asked for.
		 *
		 * @return true if the user answered no or install could not ask them
		 */
		boolean withheld()
		{
			return declined || unasked;
		}

		/**
		 * Returns whether the user answered no.
		 *
		 * @return true if the user answered no in the terminal
		 */
		boolean declined()
		{
			return declined;
		}
	}

	/**
	 * A harness's installation whose policy passed the checks, or the refusal of an operation on it.
	 *
	 * @param policy     the installation's policy, or null for a refusal
	 * @param activation the harness's activation provider, or null for a refusal
	 * @param home       the harness home that the policy records, or null for a refusal
	 * @param session    runs the harness program that install recorded, or null for a refusal or an operation that
	 *                   only reads the installation
	 * @param refusal    the refusal, or null when the checks passed
	 */
	private record Managed(TrustPolicy policy, PluginActivation activation, Path home, HarnessSession session,
		UpdateResult refusal)
	{
		/**
		 * Returns the refusal of an operation.
		 *
		 * @param refusal the refusal
		 * @return the refused installation
		 */
		static Managed refused(UpdateResult refusal)
		{
			return new Managed(null, null, null, null, refusal);
		}
	}
}
