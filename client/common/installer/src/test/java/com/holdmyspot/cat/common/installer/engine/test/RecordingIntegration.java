package com.holdmyspot.cat.common.installer.engine.test;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.ConsentRequest;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.EntryGroup;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.engine.HarnessSession;
import com.holdmyspot.cat.common.installer.engine.LinkSafeDeletion;
import com.holdmyspot.cat.common.installer.engine.TreeCopy;
import com.holdmyspot.cat.common.installer.fakes.test.HarnessProgram;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A harness integration for a generic harness, {@code harness-a} unless a test names another, that records each call
 * and fails the calls that a test names. It never runs a program. Its harness keeps CAT's installation as
 * {@link Layout} describes, with the version folders of {@code harness-a} in {@code plugins/cache/cat/cat} and those
 * of any other harness in {@code plugins/cache/<harness>/cat}, so that no folder is a version folder of two harnesses,
 * and installs a plugin as a harness does: {@code register} copies the plugin in the
 * marketplace folder into the folder of the version that it is asked to register, and records that version in
 * {@link #registeredVersion(Path)}. Each home's configuration holds the permission, sandbox and consented entries that
 * the integration added and did not remove, so each entry is added once. The harness integration contract checks this
 * behavior on this integration and on the Claude Code and Codex integrations; the failures that a test names and the
 * recorded calls are test controls and observations.
 */
public final class RecordingIntegration implements HarnessIntegration
{
	/**
	 * The harness.
	 */
	private final String harness;
	/**
	 * Each call's name followed by its home, in call order.
	 */
	private final List<String> calls = new ArrayList<>();
	/**
	 * The calls that throw an {@code IOException}, as a harness program that fails would.
	 */
	private final Set<String> failing = new HashSet<>();
	/**
	 * The calls that throw an unchecked exception, standing in for a process that stops mid-operation.
	 */
	private final Set<String> crashing = new HashSet<>();
	/**
	 * The entries that {@code permissions} reports as maintained, which {@code installPermissions} adds.
	 */
	private final Map<String, List<String>> maintained = new LinkedHashMap<>();
	/**
	 * The entries that each home's configuration holds, by home.
	 */
	private final Map<Path, Map<String, List<String>>> configurations = new HashMap<>();
	/**
	 * The entries that each {@code removePermissions} call received.
	 */
	private final List<Map<String, List<String>>> removed = new ArrayList<>();
	/**
	 * The sandbox entries that {@code sandboxEntries} reports as maintained, which {@code installSandboxEntries} adds.
	 */
	private Map<String, List<String>> sandbox = Map.of();
	/**
	 * The version that each {@code register} call received, in call order.
	 */
	private final List<PluginVersion> registered = new ArrayList<>();
	/**
	 * The question that {@code consentRequest} returns, or null when install needs no permission.
	 */
	private String question;
	/**
	 * Whether install stops when the user withholds the permission that {@code question} asks for.
	 */
	private boolean required = true;
	/**
	 * The entries that {@code installConsentedEntries} adds, which {@code sandboxEntries} also reports as maintained
	 * once they are added.
	 */
	private Map<String, List<String>> consented = Map.of();
	/**
	 * The sentence that {@code describeAdded} returns when the added entries hold a consented entry.
	 */
	private String consentNotice = "";
	/**
	 * The sentence that {@code describeAdded} returns for each added entry key, standing for an entry that a setting
	 * of the user's shares.
	 */
	private final Map<String, String> notices = new LinkedHashMap<>();
	/**
	 * The sentence that {@code describeRetained} returns for each entry key that the user changed after install
	 * added it.
	 */
	private final Map<String, String> changed = new LinkedHashMap<>();
	/**
	 * Whether {@code installReadOnlyEntries} adds entries for its files.
	 */
	private boolean readOnly;
	/**
	 * The {@code keepData} argument of each {@code unregister} call, in call order.
	 */
	private final List<Boolean> keptData = new ArrayList<>();
	/**
	 * Whether {@code unregister} deletes the plugin data directory unless it is asked to keep it, as Claude Code does.
	 */
	private boolean deletesData;
	/**
	 * Whether the harness runs the plugin from the version folder with the greatest name, as Codex does, instead of
	 * the version that it registered.
	 */
	private boolean greatestName;

	/**
	 * Creates an integration of {@code harness-a} whose calls all succeed.
	 */
	public RecordingIntegration()
	{
		this("harness-a");
	}

	/**
	 * Creates an integration whose calls all succeed.
	 *
	 * @param harness the harness
	 */
	public RecordingIntegration(String harness)
	{
		this.harness = harness;
	}

	/**
	 * Makes a call fail with an {@code IOException} until {@link #succeed(String)} is called.
	 *
	 * @param call the call's name, such as {@code register}
	 * @return this integration
	 */
	public RecordingIntegration fail(String call)
	{
		failing.add(call);
		return this;
	}

	/**
	 * Makes a call stop with an unchecked exception, as if the process ended during it.
	 *
	 * @param call the call's name
	 * @return this integration
	 */
	public RecordingIntegration crash(String call)
	{
		crashing.add(call);
		return this;
	}

	/**
	 * Makes a call succeed again.
	 *
	 * @param call the call's name
	 * @return this integration
	 */
	public RecordingIntegration succeed(String call)
	{
		failing.remove(call);
		crashing.remove(call);
		return this;
	}

	/**
	 * Adds entries to those that the integration maintains, which the next permission installation adds to each home
	 * whose configuration lacks them.
	 *
	 * @param entries each configuration's maintained entries
	 * @return this integration
	 */
	public RecordingIntegration maintain(Map<String, List<String>> entries)
	{
		for (Map.Entry<String, List<String>> entry : entries.entrySet())
		{
			List<String> values = new ArrayList<>(maintained.getOrDefault(entry.getKey(), List.of()));
			for (String value : entry.getValue())
			{
				if (!values.contains(value))
					values.add(value);
			}
			maintained.put(entry.getKey(), List.copyOf(values));
		}
		return this;
	}

	/**
	 * Sets the sandbox entries that the integration maintains, which the next sandbox installation adds to each home
	 * whose configuration lacks them.
	 *
	 * @param entries each configuration's sandbox entries
	 * @return this integration
	 */
	public RecordingIntegration sandboxing(Map<String, List<String>> entries)
	{
		this.sandbox = Map.copyOf(entries);
		return this;
	}

	/**
	 * Makes install ask the user a question before it changes anything, as a configuration that shares the setting
	 * that CAT must change does, and sets the entries that it adds once the user agrees.
	 *
	 * @param question the question
	 * @param entries  the entries that {@code installConsentedEntries} adds
	 * @param notice   the sentence that tells the user that the entries change a shared setting
	 * @return this integration
	 */
	public RecordingIntegration asking(String question, Map<String, List<String>> entries, String notice)
	{
		this.question = question;
		this.required = true;
		this.consented = Map.copyOf(entries);
		this.consentNotice = notice;
		return this;
	}

	/**
	 * Makes install ask the user a question before it changes anything, as {@link #asking} does, for a change without
	 * which install goes on.
	 *
	 * @param question the question
	 * @param entries  the entries that {@code installConsentedEntries} adds
	 * @param notice   the sentence that tells the user that the entries change a shared setting
	 * @return this integration
	 */
	public RecordingIntegration askingOptionally(String question, Map<String, List<String>> entries, String notice)
	{
		asking(question, entries, notice);
		this.required = false;
		return this;
	}

	/**
	 * Makes an entry that installation adds one that a setting of the user's shares, so that the user is told about
	 * it.
	 *
	 * @param key      the entry's key
	 * @param sentence the sentence that {@code describeAdded} returns when the key is among the added entries
	 * @return this integration
	 */
	public RecordingIntegration describing(String key, String sentence)
	{
		notices.put(key, sentence);
		return this;
	}

	/**
	 * Stands for the user's change of an entry that install added, so that uninstall tells the user that it left it.
	 *
	 * @param key      the entry's key
	 * @param sentence the sentence that {@code describeRetained} returns when the key is among the added entries
	 * @return this integration
	 */
	public RecordingIntegration retaining(String key, String sentence)
	{
		changed.put(key, sentence);
		return this;
	}

	/**
	 * Makes {@code installReadOnlyEntries} add an entry for each of its files.
	 *
	 * @return this integration
	 */
	public RecordingIntegration denyingWrites()
	{
		this.readOnly = true;
		return this;
	}

	/**
	 * Makes {@code unregister} delete the plugin data directory unless it is asked to keep it, as Claude Code's
	 * plugin uninstall does.
	 *
	 * @return this integration
	 */
	public RecordingIntegration deletingData()
	{
		this.deletesData = true;
		return this;
	}

	/**
	 * Makes the harness run the plugin from the version folder with the greatest name, as Codex does, instead of the
	 * version that it registered.
	 *
	 * @return this integration
	 */
	public RecordingIntegration runningGreatestName()
	{
		this.greatestName = true;
		return this;
	}

	/**
	 * Returns the {@code keepData} argument of each unregistration.
	 *
	 * @return the arguments, in call order
	 */
	public List<Boolean> keptData()
	{
		return List.copyOf(keptData);
	}

	/**
	 * Returns the calls made so far with what each received.
	 *
	 * @return each call's name followed by a space and its subject, such as the harness home, in call order
	 */
	public List<String> callsWithSubjects()
	{
		return List.copyOf(calls);
	}

	/**
	 * Returns the calls made so far.
	 *
	 * @return each call's name, in call order
	 */
	public List<String> calls()
	{
		return calls.stream().map(call -> call.substring(0, call.indexOf(' '))).toList();
	}

	/**
	 * Returns the versions that registration was asked to register.
	 *
	 * @return each {@code register} call's version, in call order
	 */
	public List<PluginVersion> registered()
	{
		return List.copyOf(registered);
	}

	/**
	 * Returns the entries that each permission removal received.
	 *
	 * @return the removed entries, in call order
	 */
	public List<Map<String, List<String>>> removed()
	{
		return List.copyOf(removed);
	}

	/**
	 * Returns a harness session that uses this integration, a harness program that always succeeds and a replacer that
	 * never waits.
	 *
	 * @return the session
	 */
	public HarnessSession session()
	{
		return new HarnessSession(this, cli(), new FileReplacer(Platform.current(), () ->
		{
		}, Clock.systemUTC()));
	}

	/**
	 * Creates the {@code harness-a} program file that install finds on the search path of the platform that runs the
	 * test. Install never starts it.
	 *
	 * @param directory the directory to create the program in
	 * @return the program
	 * @throws IOException if the program cannot be written
	 */
	public static Path program(Path directory) throws IOException
	{
		// Windows finds only a program with one of the extensions that PATHEXT names
		String name = "harness-a";
		if (Platform.current().isWindows())
			name += ".exe";
		Path program = Files.writeString(Files.createDirectories(directory).resolve(name), "#!/bin/sh\n");
		if (Files.getFileStore(program).supportsFileAttributeView(PosixFileAttributeView.class))
			Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
		return program;
	}

	/**
	 * Returns the variables of the test process's environment that install needs on Windows to record a program.
	 *
	 * @return {@code SystemRoot} on Windows, or no variable elsewhere
	 */
	public static Map<String, String> windowsVariables()
	{
		String systemRoot = System.getenv("SystemRoot");
		if (!Platform.current().isWindows() || systemRoot == null)
			return Map.of();
		return Map.of("SystemRoot", systemRoot);
	}

	/**
	 * Returns the variables that install records for a program on Windows, beside the harness's own variables and
	 * {@code PATH}, when the environment holds {@link #windowsVariables()}.
	 *
	 * @return the variables on Windows, or no variable elsewhere
	 */
	public static Map<String, String> recordedWindowsVariables()
	{
		Map<String, String> variables = new LinkedHashMap<>(windowsVariables());
		if (Platform.current().isWindows())
			variables.put("NoDefaultCurrentDirectoryInExePath", "1");
		return variables;
	}

	/**
	 * Returns where a harness keeps CAT's installation.
	 *
	 * @param harness the harness
	 * @param home    the absolute harness home
	 * @return the layout
	 */
	public static Layout layout(String harness, Path home)
	{
		String cacheFolder = "cat";
		if (!harness.equals("harness-a"))
			cacheFolder = harness;
		return new Layout(home, cacheFolder);
	}

	/**
	 * Returns the runner of a {@code harness-a} program that exits with status 0 and no output for every command. The
	 * runner is the installer's own, and only the program's process is a stand-in, so no program starts.
	 *
	 * @return the runner
	 */
	public static HarnessCli cli()
	{
		return HarnessProgram.cli(_ -> new CommandResult(0, ""));
	}

	@Override
	public String harness()
	{
		return harness;
	}

	@Override
	public String productName()
	{
		return "Product " + harness;
	}

	@Override
	public String agentSessionVariable()
	{
		return agentSessionVariable(harness);
	}

	/**
	 * Returns the variable in which a harness's agent sessions give the commands that they run an identifier.
	 *
	 * @param harness the harness
	 * @return the variable name
	 */
	public static String agentSessionVariable(String harness)
	{
		return "AGENT_SESSION_" + harness;
	}

	@Override
	public HarnessInstallation installation(Path home)
	{
		return layout(harness, home);
	}

	/**
	 * Returns the installation at the home that {@code <HARNESS>_HOME} names, or else at
	 * {@code <user home>/<harness>-home}.
	 */
	@Override
	public HarnessInstallation installation(Map<String, String> environment, Path userHome)
	{
		return layout(harness, HarnessInstallation.resolveHome(environment, homeVariable(), userHome,
			Path.of(harness + "-home")));
	}

	@Override
	public Path marketplaceFolder(Path home, PluginVersion version)
	{
		return layout(harness, home).marketplacePlugin();
	}

	@Override
	public String programName()
	{
		return "harness-a";
	}

	/**
	 * Returns {@code HARNESS_A_HOME}, naming the harness home.
	 */
	@Override
	public Map<String, String> programEnvironment(Path home, Map<String, String> environment)
	{
		return Map.of(homeVariable(), home.toString());
	}

	/**
	 * Returns the variable that names the harness home, such as {@code HARNESS_A_HOME}.
	 *
	 * @return the variable's name
	 */
	private String homeVariable()
	{
		return harness.toUpperCase(Locale.ROOT).replace('-', '_') + "_HOME";
	}

	/**
	 * Records the call as {@code requireIdentity} and fails it when the test asked for that.
	 */
	@Override
	public void requireIdentity(CommandResult version) throws IOException
	{
		record("requireIdentity", "exit status " + version.exitCode());
		if (version.exitCode() != 0)
			throw new IOException(programName() + " --version failed with exit status " + version.exitCode());
	}

	@Override
	public void validateConfiguration(Path home) throws IOException
	{
		record("validateConfiguration", home);
	}

	/**
	 * Records the call and, when the marketplace folder holds a plugin, copies it into the folder of
	 * {@code version}, replacing an earlier copy.
	 */
	@Override
	public void register(Path home, PluginVersion version, HarnessCli cli, FileReplacer replacer) throws IOException
	{
		record("register", home);
		registered.add(version);
		Layout layout = layout(harness, home);
		Path source = layout.marketplacePlugin();
		if (!Files.isRegularFile(source.resolve(PluginDirectory.VERSION)))
			throw new IOException("The marketplace folder holds no plugin: " + source);
		Path target = layout.versionFolder(version);
		Files.createDirectories(target.getParent());
		LinkSafeDeletion.delete(target, target.getParent());
		TreeCopy.copy(source, target);
		Files.writeString(registeredVersion(home), version + "\n");
	}

	/**
	 * Returns the file in which the harness records the version that it was last asked to install.
	 *
	 * @param home the harness home
	 * @return the file
	 */
	public static Path registeredVersion(Path home)
	{
		return home.resolve("registered-version");
	}

	@Override
	public void unregister(Path home, HarnessCli cli, boolean keepData) throws IOException
	{
		record("unregister", home);
		keptData.add(keepData);
		if (!keepData && deletesData)
			LinkSafeDeletion.delete(layout(harness, home).dataDirectory(), home);
	}

	@Override
	public boolean runsInstead(PluginVersion candidate, PluginVersion active)
	{
		return greatestName && candidate.toString().compareTo(active.toString()) > 0;
	}

	@Override
	public Map<String, List<String>> installPermissions(Path home, Path installer, FileReplacer replacer)
		throws IOException
	{
		record("installPermissions", installer);
		return add(home, maintained);
	}

	@Override
	public Map<String, List<String>> permissions(Path installer)
	{
		return Map.copyOf(maintained);
	}

	/**
	 * Records the call with the other harness's home, the lock directory and the launcher's state directory, and
	 * returns the entries that the test set.
	 */
	@Override
	public Map<String, List<String>> installSandboxEntries(Path home, HarnessInstallation other, Path catLocks,
		Path launcherState, FileReplacer replacer) throws IOException
	{
		record("installSandboxEntries", other.home() + " " + catLocks + " " + launcherState);
		return add(home, sandbox);
	}

	@Override
	public Map<String, List<String>> sandboxEntries(Path home, HarnessInstallation other, Path catLocks,
		Path launcherState)
	{
		if (!consentedAdded(home))
			return sandbox;
		Map<String, List<String>> entries = new LinkedHashMap<>(sandbox);
		entries.putAll(consented);
		return entries;
	}

	/**
	 * Records the call with the files, and adds the entries that {@code readOnlyEntries} returns.
	 */
	@Override
	public Map<String, List<String>> installReadOnlyEntries(Path home, List<Path> files, FileReplacer replacer)
		throws IOException
	{
		record("installReadOnlyEntries", files.toString());
		return add(home, readOnlyEntries(home, files));
	}

	/**
	 * Returns an entry {@code read-only.json#denied} for each file once the test called {@link #denyingWrites()},
	 * otherwise none.
	 */
	@Override
	public Map<String, List<String>> readOnlyEntries(Path home, List<Path> files)
	{
		if (!readOnly || files.isEmpty())
			return Map.of();
		return Map.of("read-only.json#denied", files.stream().map(Path::toString).toList());
	}

	/**
	 * Returns the question that the test set, until the user's change was made.
	 */
	@Override
	public Optional<ConsentRequest> consentRequest(Path home, Path catLocks)
	{
		if (consentedAdded(home) || question == null)
			return Optional.empty();
		return Optional.of(new ConsentRequest(question, required));
	}

	/**
	 * Records the call and returns the entries that the test set.
	 */
	@Override
	public Map<String, List<String>> installConsentedEntries(Path home, FileReplacer replacer) throws IOException
	{
		record("installConsentedEntries", home);
		if (question == null)
			return Map.of();
		return add(home, consented);
	}

	@Override
	public List<String> describeAdded(Path home, Map<String, List<String>> added)
	{
		List<String> sentences = new ArrayList<>(describe(notices, added));
		if (consented.keySet().stream().anyMatch(added::containsKey))
			sentences.add(consentNotice);
		return List.copyOf(sentences);
	}

	/**
	 * Returns a group of the values that each key records, as values that its setting is set to.
	 */
	@Override
	public List<EntryGroup> describeEntries(Map<String, List<String>> recorded, Path installer)
	{
		List<EntryGroup> groups = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : recorded.entrySet())
		{
			String[] location = entry.getKey().split("#", 2);
			String setting = "";
			if (location.length == 2)
				setting = location[1];
			groups.add(new EntryGroup(EntryEffect.SETS_VALUE, Path.of(location[0]), setting, entry.getValue(),
				entry.getValue()));
		}
		return groups;
	}

	@Override
	public List<String> describeRetained(Path home, Map<String, List<String>> added)
	{
		return describe(changed, added);
	}

	/**
	 * Returns the sentences for the keys among the added entries.
	 *
	 * @param sentences each key's sentence
	 * @param added     the added entries
	 * @return the sentences, in the order in which the test set them
	 */
	private static List<String> describe(Map<String, String> sentences, Map<String, List<String>> added)
	{
		return sentences.entrySet().stream().filter(entry -> added.containsKey(entry.getKey())).
			map(Map.Entry::getValue).toList();
	}

	@Override
	public void removePermissions(Path home, Map<String, List<String>> entries, FileReplacer replacer)
		throws IOException
	{
		record("removePermissions", home);
		removed.add(Map.copyOf(entries));
		Map<String, List<String>> configuration = configuration(home);
		for (Map.Entry<String, List<String>> entry : entries.entrySet())
		{
			List<String> values = new ArrayList<>(configuration.getOrDefault(entry.getKey(), List.of()));
			values.removeAll(entry.getValue());
			if (values.isEmpty())
				configuration.remove(entry.getKey());
			else
				configuration.put(entry.getKey(), List.copyOf(values));
		}
	}

	/**
	 * Adds the entries that a home's configuration lacks.
	 *
	 * @param home    the harness home
	 * @param entries the entries to hold
	 * @return the entries that were added
	 */
	private Map<String, List<String>> add(Path home, Map<String, List<String>> entries)
	{
		Map<String, List<String>> configuration = configuration(home);
		Map<String, List<String>> added = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> entry : entries.entrySet())
		{
			List<String> values = new ArrayList<>(configuration.getOrDefault(entry.getKey(), List.of()));
			List<String> missing = entry.getValue().stream().filter(value -> !values.contains(value)).toList();
			if (missing.isEmpty())
				continue;
			values.addAll(missing);
			configuration.put(entry.getKey(), List.copyOf(values));
			added.put(entry.getKey(), missing);
		}
		return Map.copyOf(added);
	}

	/**
	 * Returns whether a home's configuration holds every consented entry.
	 *
	 * @param home the harness home
	 * @return true if the consented entries were added and not removed
	 */
	private boolean consentedAdded(Path home)
	{
		if (question == null)
			return false;
		Map<String, List<String>> configuration = configuration(home);
		return consented.entrySet().stream().allMatch(entry ->
			configuration.getOrDefault(entry.getKey(), List.of()).containsAll(entry.getValue()));
	}

	/**
	 * Returns the entries that a home's configuration holds.
	 *
	 * @param home the harness home
	 * @return the modifiable entries
	 */
	private Map<String, List<String>> configuration(Path home)
	{
		return configurations.computeIfAbsent(home, _ -> new LinkedHashMap<>());
	}

	/**
	 * Records a call and fails it when the test asked for that.
	 *
	 * @param call the call's name
	 * @param subject what it received, such as the harness home
	 * @throws IOException if the test made the call fail
	 */
	private void record(String call, Object subject) throws IOException
	{
		calls.add(call + " " + subject);
		if (crashing.contains(call))
			throw new IllegalStateException(harness + " stopped during " + call);
		if (failing.contains(call))
			throw new IOException(harness + " refused " + call);
	}

	/**
	 * Where the generic harness keeps CAT's installation: its marketplace in {@code plugins/cat-marketplace}, with the
	 * plugin in {@code plugins/cat}, the version folders in {@code plugins/cache/<cache folder>/cat} and the plugin
	 * data in {@code plugins/data/cat-cat}. The harness also keeps a copy of each version in {@code plugins/copies},
	 * named {@code copy-} and then the version, which stands for a harness, such as Claude Code, that keeps its own
	 * copies under other names.
	 *
	 * @param home        the absolute harness home
	 * @param cacheFolder the folder in {@code plugins/cache} that holds the folder of the version folders
	 */
	public record Layout(Path home, String cacheFolder) implements HarnessInstallation
	{
		/**
		 * The text before the version in the name of the harness's copy of a plugin version.
		 */
		private static final String COPY_PREFIX = "copy-";

		/**
		 * Creates the layout of {@code harness-a}, whose version folders lie in {@code plugins/cache/cat/cat}.
		 *
		 * @param home the absolute harness home
		 */
		public Layout(Path home)
		{
			this(home, "cat");
		}

		/**
		 * Creates the layout.
		 *
		 * @param home        the absolute harness home
		 * @param cacheFolder the folder in {@code plugins/cache} that holds the folder of the version folders
		 */
		public Layout
		{
			if (!home.isAbsolute())
				throw new IllegalArgumentException("home must be absolute: " + home);
			home = home.normalize();
		}

		@Override
		public Path marketplaceRoot()
		{
			return home.resolve("plugins/cat-marketplace");
		}

		@Override
		public Path marketplacePlugin()
		{
			return marketplaceRoot().resolve("plugins/cat");
		}

		@Override
		public Path cacheRoot()
		{
			return home.resolve("plugins/cache");
		}

		@Override
		public Path versionFolders()
		{
			return cacheRoot().resolve(cacheFolder).resolve("cat");
		}

		@Override
		public List<Path> versionCopyFolders()
		{
			return List.of(versionFolders(), copies());
		}

		@Override
		public Optional<PluginVersion> copiedVersion(Path folder, String name)
		{
			if (!folder.equals(copies()))
				return HarnessInstallation.super.copiedVersion(folder, name);
			if (!name.startsWith(COPY_PREFIX))
				return Optional.empty();
			return HarnessInstallation.super.copiedVersion(folder, name.substring(COPY_PREFIX.length()));
		}

		@Override
		public Path dataDirectory()
		{
			return home.resolve("plugins/data/cat-cat");
		}

		/**
		 * Returns the harness's copy of a plugin version.
		 *
		 * @param version the plugin version
		 * @return the copy's folder
		 */
		public Path copy(PluginVersion version)
		{
			return copies().resolve(COPY_PREFIX + version);
		}

		/**
		 * Returns the folder of the harness's own copies of the plugin versions.
		 *
		 * @return the folder
		 */
		private Path copies()
		{
			return home.resolve("plugins/copies");
		}
	}
}
