package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installation.CapacityLocks;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
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
import com.holdmyspot.cat.common.installer.engine.PermissionRecord;
import com.holdmyspot.cat.common.installer.engine.PluginInstall;
import com.holdmyspot.cat.common.installer.engine.ProcessElevation;
import com.holdmyspot.cat.common.installer.engine.ProcessMigrationRunner;
import com.holdmyspot.cat.common.installer.engine.ReleaseConnection;
import com.holdmyspot.cat.common.installer.engine.ShellStartupFiles;
import com.holdmyspot.cat.common.installer.engine.StagedInstall;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.TrustedKey;
import com.holdmyspot.cat.common.release.EntryCommand;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
 * version folder acts only on the harness whose version folders contain it, except that it installs CAT, of its own
 * release, into another harness that has none.
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
	private final Platform platform;
	private final Map<String, String> environment;
	private final EntryCommand entryCommand;
	private final List<PluginActivation> activations;
	private final List<HarnessIntegration> integrations;
	private final HarnessCliFactory cliFactory;
	private final Clock clock;
	private final Waiting waiting;
	private final FileReplacer replacer;
	private final Terminal terminal;
	private final ProcessStarter starter;
	private final ReleaseSources sources;
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
	 * The user's home directory, which the environment names in the platform's home variable, as it does for the
	 * runtime; known once install or {@code status --verbose} has checked that the environment sets it.
	 */
	private Path userHome;
	/**
	 * The permission and sandbox entries that the report to a person lists, once the operation that lists them has
	 * read them.
	 */
	private ReportedEntries reportedEntries;
	/**
	 * Whether the result is described to a person instead of printed as JSON.
	 */
	private boolean describesToPerson;

	/**
	 * Creates one process invocation that downloads releases over HTTPS, with its other boundaries explicit.
	 *
	 * @param image        the plugin folder that the running installer runs from: the extracted plugin archive for
	 *                     install, otherwise the version folder of the installed plugin
	 * @param platform     the running platform
	 * @param environment  the boundary-captured environment; routine path authority never comes from this map
	 * @param accountHome  the home directory that the user database records for the account that runs the installer,
	 *                     or null if it cannot be determined; a message that tells the user to run the CAT entry
	 *                     command writes the environment's {@code HOME} into it unless {@code HOME} is this directory
	 * @param activations  the installed activation services
	 * @param integrations the installed harness integration services
	 * @param cliFactory   runs the recorded harness program
	 * @param clock        supplies UTC diagnostic times
	 * @param waiting      creates pauses between installer lock attempts
	 * @param replacer     atomically replaces installed files, with platform-appropriate retry waiting
	 * @param terminal     asks the user whether they allow a change that needs their permission, and tells whether the
	 *                     user reads the standard output
	 * @param starter      starts the program that deletes, once the installer's process has exited, the folders that
	 *                     it cannot delete while it runs
	 * @param stdout       receives exactly one JSON result, or a report to a person where the terminal shows it
	 * @param stderr       receives human diagnostics
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Invocation(Path image, Platform platform, Map<String, String> environment, Path accountHome,
		List<PluginActivation> activations, List<HarnessIntegration> integrations, HarnessCliFactory cliFactory,
		Clock clock, Waiting waiting, FileReplacer replacer, Terminal terminal, ProcessStarter starter,
		PrintStream stdout, PrintStream stderr)
	{
		this(image, platform, environment, accountHome, activations, integrations, cliFactory, clock, waiting,
			replacer, terminal, starter, releaseBaseUrl -> new HttpsReleaseSource(releaseBaseUrl, environment,
				CONNECT_LIMIT, REQUEST_LIMIT), stdout, stderr);
	}

	/**
	 * Creates one process invocation with explicit boundaries, including the release source, for isolated tests.
	 *
	 * @param image        the plugin folder that the running installer runs from: the extracted plugin archive for
	 *                     install, otherwise the version folder of the installed plugin
	 * @param platform     the running platform
	 * @param environment  the boundary-captured environment; routine path authority never comes from this map
	 * @param accountHome  the home directory that the user database records for the account that runs the installer,
	 *                     or null if it cannot be determined; a message that tells the user to run the CAT entry
	 *                     command writes the environment's {@code HOME} into it unless {@code HOME} is this directory
	 * @param activations  the installed activation services
	 * @param integrations the installed harness integration services
	 * @param cliFactory   runs the recorded harness program
	 * @param clock        supplies UTC diagnostic times
	 * @param waiting      creates pauses between installer lock attempts
	 * @param replacer     atomically replaces installed files, with platform-appropriate retry waiting
	 * @param terminal     asks the user whether they allow a change that needs their permission, and tells whether the
	 *                     user reads the standard output
	 * @param starter      starts the program that deletes, once the installer's process has exited, the folders that
	 *                     it cannot delete while it runs
	 * @param sources      opens the release source at a policy's release location
	 * @param stdout       receives exactly one JSON result, or a report to a person where the terminal shows it
	 * @param stderr       receives human diagnostics
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Invocation(Path image, Platform platform, Map<String, String> environment, Path accountHome,
		List<PluginActivation> activations, List<HarnessIntegration> integrations, HarnessCliFactory cliFactory,
		Clock clock, Waiting waiting, FileReplacer replacer, Terminal terminal, ProcessStarter starter,
		ReleaseSources sources, PrintStream stdout, PrintStream stderr)
	{
		requireThat(image, "image").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(cliFactory, "cliFactory").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(terminal, "terminal").isNotNull();
		requireThat(starter, "starter").isNotNull();
		requireThat(sources, "sources").isNotNull();
		requireThat(stdout, "stdout").isNotNull();
		requireThat(stderr, "stderr").isNotNull();
		this.image = image;
		this.platform = platform;
		this.environment = Map.copyOf(environment);
		this.entryCommand = new EntryCommand(platform, this.environment, accountHome);
		this.activations = List.copyOf(activations);
		this.integrations = List.copyOf(integrations);
		this.cliFactory = cliFactory;
		this.clock = clock;
		this.waiting = waiting;
		this.replacer = replacer;
		this.terminal = terminal;
		this.starter = starter;
		this.sources = sources;
		this.stdout = stdout;
		this.stderr = stderr;
	}

	/**
	 * Runs one complete command, reporting every operational failure as one JSON result, or as a report to a person
	 * where the terminal shows the standard output and the command does not ask for {@code --json}.
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
			describesToPerson = readerIsPerson(arguments.json());
		}
		catch (IllegalArgumentException e)
		{
			describesToPerson = readerIsPerson(Arrays.asList(args).contains("--json"));
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
			case INSTALL -> failure(Result.FAILED, harness, message + "; " +
				Locations.rerunEntryCommand(entryCommand, harness) + " to continue.", journals);
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
		Managed managed = manage(own, locations.policy(), arguments.operation() == Operation.STATUS);
		if (managed.refusal() != null)
			return print(managed.refusal(), arguments, locations);
		if (arguments.operation() == Operation.STATUS)
		{
			try (ReleaseConnection source = sources.open(managed.policy().releaseBaseUrl()))
			{
				UpdateEngine engine = new UpdateEngine(locations.data(), managed.policy(), source, managed.activation(),
					platform.id(), clock, waiting);
				UpdateResult status = engine.status();
				ObjectNode object = encode(status);
				if (arguments.verbose() && status.version().isPresent())
				{
					// The report abbreviates the user's home folder in the entries' paths
					Optional<UpdateResult> homeRefusal = readUserHome(own);
					if (homeRefusal.isPresent())
						return print(homeRefusal.get(), arguments, locations);
					addRecordedEntries(object, locations, managed.home());
				}
				return print(status, object, arguments, locations);
			}
		}
		HarnessSession session = managed.session();
		UninstallResult result = new Uninstaller(locations.data(), managed.policy(), managed.activation(), session,
			locations.image(), clock, waiting).uninstall(arguments.deleteData());
		return printUninstall(result, arguments, locations);
	}

	/**
	 * Adds to a status the permission and sandbox entries that install recorded for the installation that the running
	 * installer belongs to, which the report to a person lists as the harness configuration holds them.
	 *
	 * @param object    the status's encoding, which receives the entries
	 * @param locations the installation's locations
	 * @param home      the installation's harness home
	 * @throws IOException if the record cannot be read
	 */
	private void addRecordedEntries(ObjectNode object, Locations locations, Path home) throws IOException
	{
		String harness = locations.harness();
		Map<String, List<String>> recorded = PermissionRecord.read(InstallerState.of(locations.data(), harness).
			permissions());
		putPermissions(object, recorded);
		HarnessIntegration integration = integrations.stream().
			filter(provider -> provider.harness().equals(harness)).findFirst().orElseThrow();
		Path installer = PluginInstall.installer(locations.image(), platform.isWindows());
		reportedEntries = new ReportedEntries(integration.describeEntries(recorded, installer), home, userHome,
			installer, true);
	}

	/**
	 * Reads the user's home folder from the environment into {@link #userHome}.
	 *
	 * @param harness the harness that the operation names
	 * @return the refusal if the environment does not set the platform's home variable to an absolute path
	 */
	private Optional<UpdateResult> readUserHome(String harness)
	{
		String refusal;
		try
		{
			Path home = platform.homeDirectory(platform.environment(environment));
			if (home.isAbsolute())
			{
				userHome = home;
				return Optional.empty();
			}
			refusal = home + " is not an absolute path";
		}
		catch (IOException e)
		{
			refusal = e.getMessage();
		}
		return Optional.of(failure(Result.REFUSED, harness, "The user's home folder is wrong: " + refusal +
			". CAT takes it from " + platform.homeVariable() + " in the environment, as the runtime does; set it to " +
			"an absolute path and run the command again.", List.of()));
	}

	/**
	 * Adds permission and sandbox entries to a result's encoding.
	 *
	 * @param object  the encoding
	 * @param entries the entries, keyed by the file and the setting that hold them
	 */
	private static void putPermissions(ObjectNode object, Map<String, List<String>> entries)
	{
		ObjectNode permissions = object.putObject("permissions");
		for (Map.Entry<String, List<String>> entry : entries.entrySet())
		{
			ArrayNode values = permissions.putArray(entry.getKey());
			entry.getValue().forEach(values::add);
		}
	}

	/**
	 * Installs a harness's plugin: through the installation whose version folder the running installer runs from, from
	 * the extracted plugin archive that the running installer belongs to, or, for a harness other than the one whose
	 * version folder the running installer runs from, from that harness's plugin of the running installer's release.
	 * Install refuses, changing nothing, unless the environment sets the platform's home variable to an absolute path,
	 * because the user's home holds the capacity locks and the harness homes that the environment does not name.
	 *
	 * @param arguments the validated install operation
	 * @return its printed result's status
	 * @throws IOException    if installation files cannot be read or replaced
	 * @throws TrustException if install refuses
	 */
	private int install(Arguments arguments) throws IOException, TrustException
	{
		Optional<UpdateResult> homeRefusal = readUserHome(arguments.harness());
		if (homeRefusal.isPresent())
			return print(homeRefusal.get(), arguments, null);
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
			return installOther(arguments, locations);
		Managed managed = manage(own, locations.policy(), false);
		if (managed.refusal() != null)
			return print(managed.refusal(), arguments, locations);
		Optional<UpdateResult> otherHome = refuseOtherHome(own, arguments.home(), managed.home());
		if (otherHome.isPresent())
			return print(otherHome.get(), arguments, locations);
		Optional<UpdateResult> unusable = refuseUnusableProgram(own, locations.policy(), managed);
		if (unusable.isPresent())
			return print(unusable.get(), arguments, locations);
		try (ReleaseConnection source = sources.open(managed.policy().releaseBaseUrl()))
		{
			UpdateEngine engine = new UpdateEngine(locations.data(), managed.policy(), source, managed.activation(),
				platform.id(), clock, waiting);
			UserConsent consent = new UserConsent(arguments.createProfile());
			UpdateResult result = engine.install(arguments.tag(), request(managed, locations.image(), consent));
			if (result.result() == Result.REFUSED && consent.withheld(result.message()))
				return print(result, withheld(result, consent, arguments, locations.image()), arguments, locations);
			return print(result, arguments, locations);
		}
	}

	/**
	 * Installs CAT into a harness other than the one whose version folder the running installer runs from. Where that
	 * harness has no CAT version, the plugin archive of that harness in the running installer's own release is
	 * downloaded from the release location of the running installer's policy, verified with the policy's release keys
	 * as every release that the installation installs is, and installed as a first install from that archive is. A tag
	 * of another release, a harness that already has CAT, which its own installer updates, and a policy that trusts no
	 * release key are refused.
	 *
	 * @param arguments the validated install operation, which names the other harness
	 * @param locations the installation that the running installer belongs to
	 * @return its printed result's status
	 * @throws IOException    if installation files cannot be read or replaced
	 * @throws TrustException if install refuses
	 */
	private int installOther(Arguments arguments, Locations locations) throws IOException, TrustException
	{
		String harness = arguments.harness();
		Optional<HarnessIntegration> integration = integrations.stream().
			filter(provider -> provider.harness().equals(harness)).findFirst();
		if (integration.isEmpty())
		{
			return print(failure(Result.REFUSED, harness, "This installer has no harness integration for " + harness +
				".", List.of()), arguments, locations);
		}
		PluginActivation activation = activations.stream().filter(provider -> provider.harness().equals(harness)).
			findFirst().orElseThrow();
		ReleaseVersion release = ReleaseVersion.parse(PluginVersion.parse(locations.image().getFileName().toString()).
			release());
		Path home = arguments.home();
		if (home == null)
			home = integration.get().installation(environment, userHome).home();
		if (home.isAbsolute() && Files.isDirectory(home))
		{
			Path real = home.toRealPath();
			Optional<String> installed = activation.installed(real).version();
			if (installed.isPresent())
			{
				return print(installedOther(arguments, integration.get().installation(real), installed.get(), release),
					arguments, locations);
			}
		}
		if (arguments.tag() != null && !arguments.tag().equals(release.tag()))
		{
			return print(failure(Result.REFUSED, harness, "This installer installs CAT into " + harness + " only " +
				"from its own release " + release + ", not from release " + arguments.tag() + "; run install " +
				"without a release.", List.of()), arguments, locations);
		}
		Managed managed = manage(locations.harness(), locations.policy(), true);
		if (managed.refusal() != null)
			return print(managed.refusal(), arguments, locations);
		if (managed.policy().keys().isEmpty())
		{
			return print(failure(Result.REFUSED, harness, "This installer's policy trusts no release key, as a " +
				"development build's does, so it cannot verify a download of the " + harness + " plugin; install " +
				"CAT into " + harness + " with the CAT entry command instead: " + entryCommand.forHarness(harness) + ".",
				List.of()), arguments, locations);
		}
		OtherInstall install = new OtherInstall(arguments, activation, integration.get());
		UpdateResult result;
		try (ReleaseConnection source = sources.open(managed.policy().releaseBaseUrl()))
		{
			UpdateEngine engine = new UpdateEngine(locations.data(), managed.policy(), source, managed.activation(),
				platform.id(), clock, waiting);
			result = engine.installOther(activation, release, install);
		}
		return install.print(result);
	}

	/**
	 * Returns the refusal of an install of CAT into another harness that already has a CAT version, naming the
	 * command of that installation's own installer that updates it to the running installer's release.
	 *
	 * @param arguments    the validated install operation
	 * @param installation the other harness's installation
	 * @param installed    the CAT version that the other harness has
	 * @param release      the running installer's release
	 * @return the refusal
	 */
	private UpdateResult installedOther(Arguments arguments, HarnessInstallation installation, String installed,
		ReleaseVersion release)
	{
		String harness = arguments.harness();
		String update = "with the CAT entry command: " + entryCommand.forHarness(harness);
		try
		{
			Path folder = installation.versionFolder(PluginVersion.parse(installed));
			if (Files.isDirectory(folder))
			{
				StringBuilder command = new StringBuilder(ConsentCommand.launcher(folder, platform));
				if (arguments.home() != null)
					command.append(" --home ").append(ConsentCommand.quote(arguments.home().toString(), platform));
				command.append(" install --harness ").append(harness).append(' ').append(release.tag());
				update = "with that installation's installer: " + command;
			}
		}
		catch (IllegalArgumentException _)
		{
			// A version without a build has no version folder
		}
		return failure(Result.REFUSED, harness, "CAT " + installed + " is already installed in " + harness + ", and " +
			"only that installation updates it; update it to release " + release + " " + update + ".", List.of());
	}

	/**
	 * Installs a harness's plugin from the extracted plugin archive that the running installer belongs to. Where the
	 * harness already has a CAT version and a valid policy of this installation, the archive's version is installed
	 * under the policy like a signed release; otherwise install writes the policy and installs the archive's version.
	 * Only the user runs the archive's installer, through the CAT entry command, so where the existing policy lists no
	 * release key, a successful install adds the signing keys that the archive's default policy names to it, keeping its
	 * other values; a policy that lists a key keeps it.
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
				Managed managed = manage(harness, installation.policy(), false);
				if (managed.refusal() == null && refuseOtherHome(harness, real, managed.home()).isEmpty())
				{
					Optional<UpdateResult> unusable = refuseUnusableProgram(harness, installation.policy(), managed);
					if (unusable.isPresent())
						return print(unusable.get(), arguments, null);
					resolvedData = installation.dataDirectory();
					resolvedHarness = harness;
					List<TrustedKey> releaseKeys = List.of();
					if (managed.policy().keys().isEmpty())
						releaseKeys = Install.defaultPolicy(image).keys();
					try (ReleaseConnection source = sources.open(managed.policy().releaseBaseUrl()))
					{
						UpdateEngine engine = new UpdateEngine(installation.dataDirectory(), managed.policy(), source,
							activation, platform.id(), clock, waiting);
						UserConsent consent = new UserConsent(arguments.createProfile());
						UpdateResult result = engine.installFrom(image, request(managed, image, consent));
						if (result.result() == Result.REFUSED && consent.withheld(result.message()))
							return print(result, withheld(result, consent, arguments, null), arguments, null);
						boolean succeeded = result.result().exitStatus() == 0;
						if (succeeded && !releaseKeys.isEmpty())
							result = addReleaseKeys(result, managed.policy(), installation.policy(), releaseKeys);
						return print(result, arguments, null);
					}
				}
			}
		}
		return installFirst(arguments, activation, integration.get());
	}

	/**
	 * Adds the signing keys of the running installer's release to an installation's policy that lists no release key,
	 * keeping the policy's other values, and adds the change to the result of the install that preceded it.
	 *
	 * @param result     the result of the install
	 * @param policy     the installation's policy, which lists no release key
	 * @param policyFile the installation's policy file
	 * @param keys       the signing keys of the running installer's release
	 * @return the result, which reports the added keys
	 * @throws IOException    if the policy cannot be written
	 * @throws TrustException if two keys have the same id
	 */
	private UpdateResult addReleaseKeys(UpdateResult result, TrustPolicy policy, Path policyFile,
		List<TrustedKey> keys) throws IOException, TrustException
	{
		Install.writePolicy(policy.withKeys(keys), policyFile, replacer);
		List<String> ids = keys.stream().map(TrustedKey::id).toList();
		String added;
		if (ids.size() == 1)
			added = "key " + ids.getFirst();
		else
			added = "keys " + String.join(", ", ids);
		List<Path> paths = new ArrayList<>(result.paths());
		paths.add(policyFile);
		return new UpdateResult(result.result(), result.harness(), result.version(), result.previousVersion(),
			result.message() + " The policy " + policyFile + " listed no release key, so install added this " +
				"release's signing " + added + " to it, which lets the installed installer verify later releases.",
			paths, result.consistent(), ids, result.aotCache(), result.exitDeletions());
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
	 * @return the checked installation, or its refusal
	 * @throws IOException    if the policy cannot be read
	 * @throws TrustException if the policy is invalid
	 */
	private Managed manage(String own, Path policyFile, boolean readOnly)
		throws IOException, TrustException
	{
		if (!Files.isRegularFile(policyFile))
		{
			return Managed.refused(failure(Result.REFUSED, own, "Installer policy is missing; " +
				Locations.rerunEntryCommand(entryCommand, own) + ".", List.of(policyFile)));
		}
		TrustPolicy policy = TrustPolicy.read(policyFile);
		if (!policy.harnesses().keySet().equals(Set.of(own)))
		{
			return Managed.refused(failure(Result.REFUSED, own, "The policy " + policyFile + " records the " +
				"harnesses " + policy.harnesses().keySet() + ", but this installation manages " + own + " only; " +
				Locations.rerunEntryCommand(entryCommand, own) + ".", List.of(policyFile)));
		}
		Optional<HarnessCommand> command = policy.command(own);
		if (command.isEmpty())
		{
			return Managed.refused(failure(Result.REFUSED, own, "The policy was written by an earlier release and " +
				"does not record the harness program; " + Locations.rerunEntryCommand(entryCommand, own) + ".",
				List.of(policyFile)));
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
		HarnessSession session = new HarnessSession(integration.get(), cliFactory.create(command.get(), home),
			replacer);
		return new Managed(policy, activation.get(), home, session, null);
	}

	/**
	 * Refuses an install whose recorded harness program can no longer be used. Install runs that program, and never
	 * takes another one from the search path once the policy records one. Uninstall needs no such check: it runs the
	 * program only to unregister the plugin, and skips that step when the program is gone.
	 *
	 * @param own        the harness
	 * @param policyFile the installation's policy
	 * @param managed    the checked installation, which records the program
	 * @return the refusal, or empty when the program can be used
	 */
	private Optional<UpdateResult> refuseUnusableProgram(String own, Path policyFile, Managed managed)
	{
		HarnessCommand command = managed.policy().command(own).orElseThrow();
		HarnessIntegration integration = integrations.stream().
			filter(provider -> provider.harness().equals(own)).findFirst().orElseThrow();
		try
		{
			HarnessProgram.requireRecorded(command, managed.home(), policyFile, integration, cliFactory, entryCommand);
			return Optional.empty();
		}
		catch (TrustException e)
		{
			return Optional.of(failure(Result.REFUSED, own, e.getMessage(),
				List.of(Path.of(command.command().getLast()), policyFile)));
		}
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
		return new InstallRequest(managed.session(), plugin, others, CapacityLocks.root(userHome),
			PluginInstall.formerLauncherState(userHome), ShellStartupFiles.in(userHome), consent);
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
		Printed printed = firstInstall(arguments, image, null, activation, integration);
		return print(printed.result(), printed.object(), arguments, null);
	}

	/**
	 * Installs a harness's plugin from an extracted plugin archive and writes the installation's policy, then
	 * completes the install as every later install does.
	 *
	 * @param arguments   the validated install operation
	 * @param plugin      the extracted plugin archive
	 * @param installer   the version folder whose installer launcher runs the install again, or null when only the
	 *                    entry command runs it
	 * @param activation  the harness's activation provider
	 * @param integration the harness's integration
	 * @return the install's outcome and its encoding
	 * @throws IOException    if installation files cannot be read or replaced
	 * @throws TrustException if install refuses
	 */
	private Printed firstInstall(Arguments arguments, Path plugin, Path installer, PluginActivation activation,
		HarnessIntegration integration) throws IOException, TrustException
	{
		String harness = arguments.harness();
		List<HarnessIntegration> others = integrations.stream().
			filter(provider -> !provider.harness().equals(harness)).toList();
		UserConsent consent = new UserConsent(arguments.createProfile());
		Install.Outcome outcome;
		try
		{
			outcome = new Install(plugin, platform, replacer, cliFactory, ProcessElevation::isElevated,
				new ProcessMigrationRunner(), entryCommand).
				install(harness, arguments.home(), environment, userHome, activation, integration, others, consent);
		}
		catch (TrustException e)
		{
			if (!consent.withheld(e.getMessage()))
				throw e;
			UpdateResult refusal = failure(Result.REFUSED, harness, e.getMessage(), List.of());
			return new Printed(refusal, withheld(refusal, consent, arguments, installer));
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
		Map<String, List<String>> recorded = outcome.entries().recorded();
		putPermissions(object, recorded);
		reportedEntries = new ReportedEntries(integration.describeEntries(recorded, outcome.installer()),
			installation.home(), userHome, outcome.installer(), false);
		return new Printed(result, object);
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
		if (consent.declined(refusal.message()))
			answer = "You answered no, so CAT changed nothing.";
		else if (plugin == null)
		{
			answer = "CAT changed nothing. To allow it, open a terminal and " +
				Locations.rerunEntryCommand(entryCommand, refusal.harness()) + "; install then asks for your permission.";
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
			// The report to a person holds the message
			if (!describesToPerson)
				stderr.println(result.message());
			appendDiagnostic(arguments, locations, result.message());
		}
		String text = ReportAudience.text(object, describesToPerson, productName(result.harness()), reportedEntries);
		stdout.println(text);
		stdout.flush();
		new ExitDeletion(platform, environment, starter, stderr).delete(result.exitDeletions());
		return result.result().exitStatus();
	}

	/**
	 * Returns whether a person, not a program, reads the standard output.
	 *
	 * @param json {@code true} if the command line asks for {@code --json}
	 * @return {@code true} if the result is described to a person
	 */
	private boolean readerIsPerson(boolean json)
	{
		return ReportAudience.isPerson(json, terminal::isStdoutTerminal, environment,
			integrations.stream().map(HarnessIntegration::agentSessionVariable).toList());
	}

	/**
	 * Returns the name by which the users of a harness know it.
	 *
	 * @param harness the harness
	 * @return the product name, or empty if this installer has no integration for the harness
	 */
	private Optional<String> productName(String harness)
	{
		return integrations.stream().filter(integration -> integration.harness().equals(harness)).findFirst().
			map(HarnessIntegration::productName);
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
		/**
		 * The questions that the user answered no to.
		 */
		private final Set<String> declined = new HashSet<>();
		/**
		 * The questions that install could not ask.
		 */
		private final Set<String> unasked = new HashSet<>();

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
			ConsentReply reply = ConsentReply.of(terminal.ask(request + " Allow this change? [y/N] "));
			if (reply == ConsentReply.ALLOWED)
				return true;
			if (reply == ConsentReply.DECLINED)
				declined.add(request);
			else
				unasked.add(request);
			return false;
		}

		/**
		 * Returns whether the user withheld a permission that install asked for.
		 *
		 * @param request the question that install asked
		 * @return true if the user answered no or install could not ask them
		 */
		boolean withheld(String request)
		{
			return declined.contains(request) || unasked.contains(request);
		}

		/**
		 * Returns whether the user answered no to a question.
		 *
		 * @param request the question that install asked
		 * @return true if the user answered no in the terminal
		 */
		boolean declined(String request)
		{
			return declined.contains(request);
		}
	}

	/**
	 * An outcome and the encoding that is printed for it.
	 *
	 * @param result the outcome
	 * @param object the outcome's encoding
	 */
	private record Printed(UpdateResult result, ObjectNode object)
	{
	}

	/**
	 * The first install of another harness's plugin from its downloaded, verified archive. It keeps the install's
	 * outcome, and any exception that the install threw, until the release's download area is released, so that they
	 * are reported as those of a first install from that archive.
	 */
	private final class OtherInstall implements StagedInstall
	{
		private final Arguments arguments;
		private final PluginActivation activation;
		private final HarnessIntegration integration;
		private Printed printed;
		private Exception failure;

		/**
		 * Creates the install.
		 *
		 * @param arguments   the validated install operation
		 * @param activation  the other harness's activation provider
		 * @param integration the other harness's integration
		 */
		OtherInstall(Arguments arguments, PluginActivation activation, HarnessIntegration integration)
		{
			this.arguments = arguments;
			this.activation = activation;
			this.integration = integration;
		}

		@Override
		public UpdateResult install(Path plugin)
		{
			try
			{
				printed = firstInstall(arguments, plugin, image, activation, integration);
				return printed.result();
			}
			catch (Exception e)
			{
				failure = e;
				return failure(Result.FAILED, arguments.harness(), "The install stopped: " + e.getMessage(),
					List.of());
			}
		}

		/**
		 * Prints the outcome of the install, or rethrows the exception that the install threw.
		 *
		 * @param result the outcome of the download and the install, whose message tells what the download area's
		 *               release adds to the install's message
		 * @return the printed result's status
		 * @throws IOException    if the install failed
		 * @throws TrustException if the install refused
		 */
		int print(UpdateResult result) throws IOException, TrustException
		{
			switch (failure)
			{
				case null ->
				{
				}
				case IOException e -> throw e;
				case TrustException e -> throw e;
				case RuntimeException e -> throw e;
				default -> throw new IOException(failure.getMessage(), failure);
			}
			if (printed == null)
				return Invocation.this.print(result, arguments, null);
			printed.object().put("message", result.message());
			return Invocation.this.print(printed.result(), printed.object(), arguments, null);
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
