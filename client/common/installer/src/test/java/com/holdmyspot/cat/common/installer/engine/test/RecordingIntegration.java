package com.holdmyspot.cat.common.installer.engine.test;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.engine.HarnessSession;
import com.holdmyspot.cat.common.installer.engine.LinkSafeDeletion;
import com.holdmyspot.cat.common.installer.engine.TreeCopy;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
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
 * {@link #registeredVersion(Path)}.
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
	 * The entries that the next {@code installPermissions} call reports as added.
	 */
	private Map<String, List<String>> added = Map.of();
	/**
	 * The entries that {@code permissions} reports as maintained: every entry that a permission installation reported
	 * as added, and the entries that a test names.
	 */
	private final Map<String, List<String>> maintained = new LinkedHashMap<>();
	/**
	 * The entries that each {@code removePermissions} call received.
	 */
	private final List<Map<String, List<String>>> removed = new ArrayList<>();
	/**
	 * The entries that the next {@code installSandboxEntries} call reports as added, which {@code sandboxEntries} also
	 * reports as maintained.
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
	 * The entries that {@code installConsentedEntries} reports as added, which {@code sandboxEntries} also reports
	 * as maintained once they are added.
	 */
	private Map<String, List<String>> consented = Map.of();
	/**
	 * Whether {@code installConsentedEntries} has added its entries.
	 */
	private boolean consentedAdded;
	/**
	 * The sentence that {@code describeAdded} returns for each added entry key.
	 */
	private final Map<String, String> notices = new LinkedHashMap<>();
	/**
	 * The sentences that {@code describeRetained} returns.
	 */
	private List<String> retained = List.of();
	/**
	 * The sentences that {@code removeRunnerExceptions} returns.
	 */
	private List<String> runnerRemovals = List.of();
	/**
	 * The {@code keepData} argument of each {@code unregister} call, in call order.
	 */
	private final List<Boolean> keptData = new ArrayList<>();
	/**
	 * The plugin data directory that {@code unregister} deletes unless it is asked to keep it, as Claude Code does, or
	 * null when it deletes none.
	 */
	private Path deletedData;
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
	 * Sets the entries that the next permission installation reports as added.
	 *
	 * @param entries each configuration's added entries
	 * @return this integration
	 */
	public RecordingIntegration adding(Map<String, List<String>> entries)
	{
		this.added = new LinkedHashMap<>(entries);
		maintain(entries);
		return this;
	}

	/**
	 * Adds entries to those that the integration reports as maintained, without reporting them as added.
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
	 * Sets the sandbox entries that the next sandbox installation reports as added and that the integration reports as
	 * maintained.
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
	 * Makes install ask the user a question before it changes anything, and sets the entries that it adds once the
	 * user agrees.
	 *
	 * @param question the question
	 * @param entries  the entries that {@code installConsentedEntries} adds
	 * @return this integration
	 */
	public RecordingIntegration asking(String question, Map<String, List<String>> entries)
	{
		this.question = question;
		this.consented = Map.copyOf(entries);
		return this;
	}

	/**
	 * Sets the sentence that tells the user about an added entry.
	 *
	 * @param key      the entry key
	 * @param sentence the sentence that {@code describeAdded} returns when the key is among the added entries
	 * @return this integration
	 */
	public RecordingIntegration describing(String key, String sentence)
	{
		notices.put(key, sentence);
		return this;
	}

	/**
	 * Sets the sentences that tell the user which runner entries install removed.
	 *
	 * @param sentences the sentences that {@code removeRunnerExceptions} returns
	 * @return this integration
	 */
	public RecordingIntegration removingRunnerExceptions(List<String> sentences)
	{
		this.runnerRemovals = List.copyOf(sentences);
		return this;
	}

	/**
	 * Sets the sentences that tell the user which entries uninstall left in place.
	 *
	 * @param sentences the sentences that {@code describeRetained} returns
	 * @return this integration
	 */
	public RecordingIntegration retaining(List<String> sentences)
	{
		this.retained = List.copyOf(sentences);
		return this;
	}

	/**
	 * Makes {@code unregister} delete a plugin data directory unless it is asked to keep it, as Claude Code's plugin
	 * uninstall does.
	 *
	 * @param data the plugin data directory
	 * @return this integration
	 */
	public RecordingIntegration deletingData(Path data)
	{
		this.deletedData = data;
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
	 * Returns a harness program that always succeeds without running anything.
	 *
	 * @return the program
	 */
	public static HarnessCli cli()
	{
		return arguments -> new CommandResult(0, "");
	}

	@Override
	public String harness()
	{
		return harness;
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
			return;
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
		if (!keepData && deletedData != null)
			LinkSafeDeletion.delete(deletedData, home);
	}

	@Override
	public boolean runsInstead(PluginVersion candidate, PluginVersion active)
	{
		return greatestName && candidate.toString().compareTo(active.toString()) > 0;
	}

	/**
	 * Records the call and returns the sentences that the test set.
	 */
	@Override
	public List<String> removeRunnerExceptions(Path home, FileReplacer replacer) throws IOException
	{
		record("removeRunnerExceptions", home);
		return runnerRemovals;
	}

	@Override
	public Map<String, List<String>> installPermissions(Path home, Path installer, FileReplacer replacer)
		throws IOException
	{
		record("installPermissions", installer);
		Map<String, List<String>> entries = added;
		added = Map.of();
		return entries;
	}

	@Override
	public Map<String, List<String>> permissions(Path installer)
	{
		return Map.copyOf(maintained);
	}

	/**
	 * Records the call with the other harness's home and the lock directory, and returns the entries that the test
	 * set.
	 */
	@Override
	public Map<String, List<String>> installSandboxEntries(Path home, HarnessInstallation other, Path catLocks,
		FileReplacer replacer) throws IOException
	{
		record("installSandboxEntries", other.home() + " " + catLocks);
		return sandbox;
	}

	@Override
	public Map<String, List<String>> sandboxEntries(Path home, HarnessInstallation other, Path catLocks)
	{
		if (!consentedAdded)
			return sandbox;
		Map<String, List<String>> entries = new LinkedHashMap<>(sandbox);
		entries.putAll(consented);
		return entries;
	}

	/**
	 * Returns the question that the test set, until the user's change was made.
	 */
	@Override
	public Optional<String> consentRequest(Path home, Path catLocks)
	{
		if (consentedAdded)
			return Optional.empty();
		return Optional.ofNullable(question);
	}

	/**
	 * Records the call and returns the entries that the test set.
	 */
	@Override
	public Map<String, List<String>> installConsentedEntries(Path home, FileReplacer replacer) throws IOException
	{
		record("installConsentedEntries", home);
		consentedAdded = true;
		return consented;
	}

	@Override
	public List<String> describeAdded(Path home, Map<String, List<String>> added)
	{
		return notices.entrySet().stream().filter(entry -> added.containsKey(entry.getKey())).
			map(Map.Entry::getValue).toList();
	}

	@Override
	public List<String> describeRetained(Path home, Map<String, List<String>> added)
	{
		return retained;
	}

	@Override
	public void removePermissions(Path home, Map<String, List<String>> entries, FileReplacer replacer)
		throws IOException
	{
		record("removePermissions", home);
		removed.add(Map.copyOf(entries));
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
