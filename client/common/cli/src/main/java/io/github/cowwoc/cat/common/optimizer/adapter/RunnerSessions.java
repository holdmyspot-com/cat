package io.github.cowwoc.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.RuleSource;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.DerivedSource;
import io.github.cowwoc.cat.common.optimizer.contract.PluginUnderTest;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;
import io.github.cowwoc.cat.common.runner.PluginManifest;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Runs nested sessions through a harness runner.
 * <p>
 * Each session runs in a fresh, isolated run directory and holds one {@link HarnessCapacity} permit for its
 * lifetime. A session of the held-out part runs under the held-out run root and is denied nothing; a session of the
 * development part runs under the run root and its harness sandbox denies it reading every held-out location. A
 * session sends the request's turns one at a time through the runner's JSONL bridge. It parses the harness's events
 * for the observed model, the token usage and the rate-limit windows, and keeps the events as the transcript. The
 * runner selects the request's profile: {@code --model} and {@code --effort} for each bound dimension, and nothing for
 * an unbound one, so the harness applies its default.
 * <p>
 * The completed result's transcript is {@code <run>/artifacts/transcript.jsonl}, its effects directory is the run's
 * {@code project} copy of the request's source, and its prompt sources are the ones that the session was observed
 * loading. When a session ends, the run's isolated home, which holds a copy of the login, is
 * deleted.
 * <p>
 * A subclass supplies its harness's event parsers, its read-only evidence options and its login classification. A
 * harness that can shorten a tool's output before its model receives it also supplies the results that its agents
 * received.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public abstract class RunnerSessions implements SessionPort
{
	/**
	 * The directory of a CAT installation that holds its rules.
	 */
	private static final String RULES = "rules";
	private final String harness;
	private final Settings settings;
	private final Bridge bridge;

	/**
	 * Creates a port.
	 *
	 * @param harness the harness's identifier, which names its directory below the run root
	 * @param settings how the runner is configured
	 * @param bridge runs the runner's bridge
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 */
	protected RunnerSessions(String harness, Settings settings, Bridge bridge)
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(settings, "settings").isNotNull();
		requireThat(bridge, "bridge").isNotNull();
		this.harness = harness;
		this.settings = settings;
		this.bridge = bridge;
	}

	@Override
	public final SessionHandle start(SessionRequest request)
	{
		requireThat(request, "request").isNotNull();
		Session session = new Session(this, request);
		session.begin();
		return session;
	}

	@Override
	public final SessionEvidence readEvidence(Path transcript) throws IOException
	{
		requireThat(transcript, "transcript").isNotNull();
		JsonMapper json = JsonMapper.builder().build();
		HarnessEvents events = newEvents(json);
		try (BufferedReader reader = Files.newBufferedReader(transcript))
		{
			String line = reader.readLine();
			while (line != null)
			{
				events.accept(line);
				line = reader.readLine();
			}
		}
		return readReceived(events.evidence(), transcript, json);
	}

	/**
	 * Adds the tool results as the session's agents received them to the activity read from its harness events.
	 * <p>
	 * This implementation returns the activity unchanged, which holds each recorded tool output as received. A harness
	 * that can shorten a tool's output before its model receives it overrides this method.
	 *
	 * @param activity   the activity read from the harness events
	 * @param transcript the file that holds the harness events
	 * @param json       the JSON mapper
	 * @return the activity with the received tool results
	 * @throws IOException if the harness's record of what its agents received cannot be read
	 */
	protected SessionEvidence readReceived(SessionEvidence activity, Path transcript, JsonMapper json)
		throws IOException
	{
		return activity;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The runner receives each held-out location through its {@code --deny-read} option. This implementation finds no
	 * setting; a harness whose host settings can outrank the runner's denial overrides it.
	 */
	@Override
	public Optional<String> findHeldOutExposure(Path project) throws IOException
	{
		requireThat(project, "project").isNotNull();
		return Optional.empty();
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The sources are the harness's instruction files at the project root followed, when the trial installs CAT, by
	 * every file that CAT's rules give the trial's agent from CAT's installation, the user's rules that the settings
	 * name and the project's rules. A built plugin's rules are read from its base with each mapped project
	 * directory written over its mapped path, as the trial installs them.
	 */
	@Override
	public final List<DerivedSource> derivedSources(Path project, PluginUnderTest plugin) throws IOException
	{
		requireThat(project, "project").isNotNull();
		Path root = project.toAbsolutePath().normalize();
		List<DerivedSource> sources = new ArrayList<>(instructionFiles(root));
		if (plugin instanceof PluginUnderTest.Built built &&
			isCat(built.base().toAbsolutePath().normalize()))
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path overlay = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
				overlayRules(built, root, overlay);
				sources.addAll(ruleSources(overlay, root, file -> builtName(built, root, overlay, file)));
			}
			return sources;
		}
		Optional<Path> catRoot = catRoot(root, plugin);
		if (catRoot.isPresent())
			sources.addAll(ruleSources(catRoot.get(), root, file -> name(root, file)));
		return sources;
	}

	/**
	 * Returns the CAT installation that a trial loads, unless the trial is a built CAT plugin: a plugin root that is
	 * CAT, the CAT artifact beside a plugin under test that depends on CAT, or the CAT artifact when a trial without
	 * a plugin under test installs CAT.
	 *
	 * @param project the absolute, normalized project directory
	 * @param plugin the trial's plugin under test, or null if it has none
	 * @return the installation directory, or empty if the trial does not install CAT
	 * @throws IOException if a manifest cannot be read
	 */
	private Optional<Path> catRoot(Path project, PluginUnderTest plugin) throws IOException
	{
		if (plugin == null)
		{
			if (settings.installCat())
				return Optional.of(settings.artifact().toAbsolutePath().normalize());
			return Optional.empty();
		}
		if (plugin instanceof PluginUnderTest.Root pluginRoot)
		{
			Path directory = project.resolve(pluginRoot.directory()).normalize();
			if (isCat(directory))
				return Optional.of(directory);
		}
		if (plugin.dependsOnCat())
			return Optional.of(settings.artifact().toAbsolutePath().normalize());
		return Optional.empty();
	}

	/**
	 * Lists the files that CAT's rules give a trial's agent.
	 *
	 * @param catRoot the absolute CAT installation directory
	 * @param project the absolute, normalized project directory
	 * @param naming names each file as the derived sources list it
	 * @return the derived sources
	 * @throws IOException if a rule cannot be read
	 */
	private List<DerivedSource> ruleSources(Path catRoot, Path project, Function<Path, String> naming)
		throws IOException
	{
		Path userRules = settings.userRules().map(path -> path.toAbsolutePath().normalize()).orElse(null);
		ProjectRuleLoader loader = new ProjectRuleLoader(YAMLMapper.builder().build());
		List<DerivedSource> sources = new ArrayList<>();
		for (RuleSource source : loader.sourcesForMainAgent(catRoot, userRules, project, harness))
		{
			String referrer = "";
			if (source.referrer().isPresent())
				referrer = naming.apply(source.referrer().get());
			sources.add(new DerivedSource(naming.apply(source.file()), describe(source.kind(), referrer)));
		}
		return sources;
	}

	/**
	 * Describes how a file reaches a trial's agent.
	 *
	 * @param kind how the file reaches the agent
	 * @param referrer the name of the file that includes, refers to or needs the file, or empty if there is none
	 * @return the rule that the derived source records
	 */
	private static String describe(RuleSource.Kind kind, String referrer)
	{
		return switch (kind)
		{
			case INJECTED -> "CAT injects this rule into every session";
			case ROUTED -> "CAT tells the agent to load this rule when it reads or edits a file that the rule's paths " +
				"match";
			case INCLUDED -> "`" + referrer + "` includes this file, which CAT injects with it";
			case REFERENCED -> "`" + referrer + "` tells the agent to load this file";
			case PREREQUISITE -> "CAT tells the agent to load this file before `" + referrer + "`";
		};
	}

	/**
	 * Names a file: relative to the project if it is inside it, and by its absolute path otherwise.
	 *
	 * @param project the absolute, normalized project directory
	 * @param file the absolute, normalized file
	 * @return the name
	 */
	private static String name(Path project, Path file)
	{
		if (file.startsWith(project))
			return PortablePaths.text(project.relativize(file));
		return file.toString();
	}

	/**
	 * Names a file of a built plugin's rule overlay: as the project file that a mapping copies to it, or else by its
	 * absolute path in the plugin's base.
	 *
	 * @param plugin the built plugin
	 * @param project the absolute, normalized project directory
	 * @param overlay the directory that holds the overlaid rules
	 * @param file the absolute, normalized file below {@code overlay}, or a file outside it
	 * @return the name
	 */
	private static String builtName(PluginUnderTest.Built plugin, Path project, Path overlay, Path file)
	{
		if (!file.startsWith(overlay))
			return name(project, file);
		Path relative = overlay.relativize(file);
		List<PluginUnderTest.Mapping> mappings = plugin.mappings();
		for (int i = mappings.size() - 1; i >= 0; --i)
		{
			PluginUnderTest.Mapping mapping = mappings.get(i);
			if (!relative.startsWith(mapping.target()) && !mapping.target().toString().isEmpty())
				continue;
			Path source = mapping.source().resolve(mapping.target().relativize(relative));
			if (Files.isRegularFile(project.resolve(source)))
				return PortablePaths.text(source);
		}
		return plugin.base().toAbsolutePath().normalize().resolve(relative).toString();
	}

	/**
	 * Copies a built plugin's rules as the trial installs them: the base's {@code rules} directory with each mapped
	 * project directory written over its mapped path.
	 *
	 * @param plugin the built plugin
	 * @param project the absolute, normalized project directory
	 * @param overlay the empty directory that receives the rules
	 * @throws IOException if the rules cannot be copied
	 */
	private static void overlayRules(PluginUnderTest.Built plugin, Path project, Path overlay) throws IOException
	{
		Path rules = Path.of(RULES);
		copyTree(plugin.base().resolve(rules), overlay.resolve(rules));
		for (PluginUnderTest.Mapping mapping : plugin.mappings())
		{
			Path source = project.resolve(mapping.source());
			Path target = mapping.target();
			if (target.startsWith(rules))
				copyTree(source, overlay.resolve(target));
			else if (target.toString().isEmpty() || rules.startsWith(target))
				copyTree(source.resolve(target.relativize(rules)), overlay.resolve(rules));
		}
	}

	/**
	 * Copies the regular files below a directory, replacing files that exist. A missing directory copies nothing.
	 *
	 * @param source the directory to copy
	 * @param target the directory that receives the files
	 * @throws IOException if a file cannot be copied
	 */
	private static void copyTree(Path source, Path target) throws IOException
	{
		if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
			return;
		try (Stream<Path> files = Files.walk(source))
		{
			for (Path file : (Iterable<Path>) files::iterator)
			{
				if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
					continue;
				Path copy = target.resolve(source.relativize(file).toString());
				Files.createDirectories(copy.getParent());
				Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	/**
	 * Returns whether a plugin copy is CAT: whether its manifest name is the name in the CAT artifact's manifest.
	 *
	 * @param copy the plugin copy
	 * @return {@code true} if the copy is CAT
	 * @throws IOException if a manifest cannot be read
	 */
	final boolean isCat(Path copy) throws IOException
	{
		for (Path manifest : PluginManifest.MANIFESTS)
		{
			if (Files.isRegularFile(settings.artifact().resolve(manifest)) && Files.isRegularFile(copy.resolve(manifest)))
				return PluginManifest.name(copy, manifest).equals(PluginManifest.name(settings.artifact(), manifest));
		}
		return false;
	}

	@Override
	public final List<Path> runRoots()
	{
		return List.of(settings.runRoot(), settings.heldOutRunRoot());
	}

	/**
	 * Returns every location that holds held-out content: the directories that {@link Settings#heldOut()} lists and
	 * the held-out run root, which is created first so that it can be denied before any session of the held-out part
	 * runs under it. Sessions of the development part are denied reading each of them.
	 *
	 * @return the real paths of the locations
	 * @throws IOException if the held-out run root cannot be created or the directories cannot be listed
	 */
	final List<Path> heldOutLocations() throws IOException
	{
		Path heldOutRunRoot = Files.createDirectories(settings.heldOutRunRoot()).toRealPath();
		List<Path> locations = new ArrayList<>(settings.heldOut().list());
		locations.add(heldOutRunRoot);
		return locations;
	}

	/**
	 * Returns the harness's identifier.
	 *
	 * @return the identifier
	 */
	final String harness()
	{
		return harness;
	}

	/**
	 * Returns how the runner is configured.
	 *
	 * @return the settings
	 */
	protected final Settings settings()
	{
		return settings;
	}

	/**
	 * Returns the runner's bridge.
	 *
	 * @return the bridge
	 */
	final Bridge bridge()
	{
		return bridge;
	}

	/**
	 * Creates the parser of one session's harness events.
	 *
	 * @param json the JSON mapper
	 * @return a parser that has seen no events
	 */
	protected abstract HarnessEvents newEvents(JsonMapper json);

	/**
	 * Creates the prompt-source observation of one run.
	 *
	 * @param json the JSON mapper
	 * @param directories maps each placeholder to a directory whose staged location must not appear in the value
	 * @return an observation that has seen no events
	 */
	protected abstract PromptSources newPromptSources(JsonMapper json, Map<String, Path> directories);

	/**
	 * Lists the instruction files that the harness reads at a project's root by convention in every session.
	 *
	 * @param project the project's root directory
	 * @return the files that exist, relative to the project, with the rule that loads each one
	 * @throws IOException if the project cannot be read
	 */
	protected abstract List<DerivedSource> instructionFiles(Path project) throws IOException;

	/**
	 * Returns the runner options that pass a request's read-only evidence.
	 *
	 * @param evidence the paths that the session may read but not change
	 * @return the options, empty if the runner has no option for read-only evidence
	 */
	protected abstract List<String> readOnlyArguments(List<Path> evidence);

	/**
	 * How a harness runner is configured. Every session of a port shares these settings; the model and reasoning
	 * effort come from each request's profile.
	 *
	 * @param artifact the complete harness artifact that the runner validates and installs
	 * @param runRoot the absolute directory that holds the run directories of sessions of the development part
	 * @param heldOut lists, when each session starts, the campaigns' held-out directories
	 * @param executable the harness executable, or blank for the harness's default command
	 * @param installCat whether the runner installs CAT into trial sessions' isolated homes
	 * @param environment the host environment that the runner scrubs for the session
	 * @param configurationDirectory the host's harness configuration directory, which holds the login
	 * @param capacity the shared capacity from which every session takes one permit
	 * @param clock the clock for time limits
	 * @param userRules the directory of the user's CAT rules, which trials that install CAT load, or empty if they load
	 *   none
	 */
	public record Settings(Path artifact, Path runRoot, HeldOutPaths heldOut, String executable, boolean installCat,
		Map<String, String> environment, Path configurationDirectory, HarnessCapacity capacity, Clock clock,
		Optional<Path> userRules)
	{
		/**
		 * Creates settings.
		 *
		 * @param artifact the complete harness artifact that the runner validates and installs
		 * @param runRoot the absolute directory that holds the run directories of sessions of the development part
		 * @param heldOut lists, when each session starts, the campaigns' held-out directories
		 * @param executable the harness executable, or blank for the harness's default command
		 * @param installCat whether the runner installs CAT into trial sessions' isolated homes
		 * @param environment the host environment that the runner scrubs for the session
		 * @param configurationDirectory the host's harness configuration directory, which holds the login
		 * @param capacity the shared capacity from which every session takes one permit
		 * @param clock the clock for time limits
		 * @param userRules the directory of the user's CAT rules, which trials that install CAT load, or empty if they
		 *   load none
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code runRoot} is not absolute or is a file system root
		 */
		public Settings
		{
			requireThat(artifact, "artifact").isNotNull();
			requireThat(runRoot, "runRoot").isNotNull();
			requireThat(runRoot.isAbsolute(), "runRoot.isAbsolute").isTrue();
			requireThat(heldOut, "heldOut").isNotNull();
			requireThat(executable, "executable").isNotNull();
			requireThat(environment, "environment").isNotNull();
			requireThat(configurationDirectory, "configurationDirectory").isNotNull();
			requireThat(capacity, "capacity").isNotNull();
			requireThat(clock, "clock").isNotNull();
			requireThat(userRules, "userRules").isNotNull();
			runRoot = runRoot.normalize();
			requireThat(runRoot.getFileName(), "runRoot.getFileName()").isNotNull();
			environment = Map.copyOf(environment);
		}

		/**
		 * Creates settings whose trials load no user rules.
		 *
		 * @param artifact the complete harness artifact that the runner validates and installs
		 * @param runRoot the absolute directory that holds the run directories of sessions of the development part
		 * @param heldOut lists, when each session starts, the campaigns' held-out directories
		 * @param executable the harness executable, or blank for the harness's default command
		 * @param installCat whether the runner installs CAT into trial sessions' isolated homes
		 * @param environment the host environment that the runner scrubs for the session
		 * @param configurationDirectory the host's harness configuration directory, which holds the login
		 * @param capacity the shared capacity from which every session takes one permit
		 * @param clock the clock for time limits
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code runRoot} is not absolute or is a file system root
		 */
		public Settings(Path artifact, Path runRoot, HeldOutPaths heldOut, String executable, boolean installCat,
			Map<String, String> environment, Path configurationDirectory, HarnessCapacity capacity, Clock clock)
		{
			this(artifact, runRoot, heldOut, executable, installCat, environment, configurationDirectory, capacity,
				clock, Optional.empty());
		}

		/**
		 * Returns the held-out run root: the directory beside the run root that holds the run directories of sessions
		 * of the held-out part. Its name is the run root's name followed by {@code -held_out}.
		 *
		 * @return the absolute directory
		 */
		public Path heldOutRunRoot()
		{
			return runRoot.resolveSibling(runRoot.getFileName() + "-held_out");
		}
	}

	/**
	 * Lists the campaigns' held-out directories. Every session of the development part runs with its harness sandbox
	 * denying reads of them.
	 */
	@FunctionalInterface
	public interface HeldOutPaths
	{
		/**
		 * Lists the directories.
		 *
		 * @return the real paths of those that exist when the method is called
		 * @throws IOException if the paths cannot be listed
		 */
		List<Path> list() throws IOException;
	}
}
