package com.holdmyspot.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.RuleSource;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.port.HookInvocation;
import com.holdmyspot.cat.common.optimizer.port.HookRegistration;
import com.holdmyspot.cat.common.optimizer.port.LoadedSources;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.UnreachableReference;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.PluginManifest;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
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
 * loading. A trial's completed result also names the host tools that its agents ran, with the versions that the host
 * environment's programs report, and the outside sources that they used. When a session ends, the run's isolated
 * home, which holds a copy of the login, is deleted.
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
	/**
	 * Separates the words of a hook's shell command.
	 */
	private static final Pattern COMMAND_SEPARATOR = Pattern.compile("[\\s;&|()<>]+");
	/**
	 * Matches a quote in a word of a hook's shell command.
	 */
	private static final Pattern QUOTE = Pattern.compile("[\"']");
	/**
	 * The event that runs a hook when a session starts.
	 */
	private static final String SESSION_START = "SessionStart";
	/**
	 * The event that runs a hook when the user submits a prompt.
	 */
	private static final String PROMPT_SUBMIT = "UserPromptSubmit";
	/**
	 * The source of a session start that a new session reports to its hooks.
	 */
	private static final String STARTUP = "startup";
	/**
	 * How long the harness lets a hook run when the hook declares no timeout.
	 */
	private static final Duration HOOK_TIMEOUT = Duration.ofSeconds(60);
	/**
	 * The session identifier in a hook's input, the same in every run so that only the session's directory differs.
	 */
	private static final String HOOK_SESSION_ID = "00000000-0000-0000-0000-000000000000";
	private final String harness;
	private final Settings settings;
	private final Bridge bridge;
	private final HostTools hostTools;

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
		this.hostTools = new HostTools(settings.environment(), HostTools.processes(settings.environment(),
			settings.runRoot().resolve("host-tools")));
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
	public final Optional<Instant> sharedHoldUntil(Instant now) throws IOException
	{
		requireThat(now, "now").isNotNull();
		Instant endsAt = settings.capacity().hold().endsAt();
		if (endsAt.isAfter(now))
			return Optional.of(endsAt);
		return Optional.empty();
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
	 * Each list holds the harness's instruction files at the project root and the project files that the project's
	 * hooks and MCP servers run, followed, when CAT is installed, by every
	 * file that CAT's rules give the primary agent from CAT's installation, the user's rules that the settings name and
	 * the project's rules. In production, CAT is installed when the settings say so, from the CAT artifact; in trials,
	 * CAT is the first trial plugin whose manifest states CAT's repository, and its rules are read from its base with
	 * each mapped project file written over its mapped path, as trials install them. A file of CAT's installation is
	 * compared across the two lists by its path relative to the installation.
	 * <p>
	 * Trials run with a new, empty harness home, so every hook of the user's harness configuration, every plugin that
	 * it enables and no trial plugin holds, and every MCP server that it declares runs in production only. An enabled
	 * plugin is held by a trial plugin of the same name, or, for a plugin that the project builds, of the same name
	 * without its marketplace.
	 */
	@Override
	public final LoadedSources loadedSources(Path project, List<PluginBase> plugins) throws IOException
	{
		requireThat(project, "project").isNotNull();
		requireThat(plugins, "plugins").isNotNull();
		Path root = project.toAbsolutePath().normalize();
		List<DerivedSource> projectFiles = new ArrayList<>(instructionFiles(root));
		projectFiles.addAll(commandFiles(root, projectFiles));

		List<DerivedSource> production = new ArrayList<>(projectFiles);
		// The name of each production file, by the key that matches it in trials
		Map<String, String> productionNames = new LinkedHashMap<>();
		for (DerivedSource source : projectFiles)
			productionNames.putIfAbsent(source.path(), source.path());
		if (settings.installCat())
		{
			Path artifact = settings.artifact().toAbsolutePath().normalize();
			for (Loaded loaded : ruleSources(artifact, root, file -> name(root, file), file -> key(root, artifact, file)))
			{
				production.add(loaded.source());
				productionNames.putIfAbsent(loaded.key(), loaded.source().path());
			}
		}

		List<DerivedSource> trials = new ArrayList<>(projectFiles);
		Set<String> trialKeys = new LinkedHashSet<>();
		for (DerivedSource source : projectFiles)
			trialKeys.add(source.path());
		// The harness loads one copy of CAT's rules
		PluginBase cat = null;
		for (PluginBase base : plugins)
		{
			if (isCat(base.directory().toAbsolutePath().normalize()))
			{
				cat = base;
				break;
			}
		}
		if (cat != null)
		{
			PluginBase base = cat;
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path overlay = cleanup.add(Files.createTempDirectory("cat-plugin-rules-"));
				overlayRules(root, base, overlay);
				for (Loaded loaded : ruleSources(overlay, root,
					file -> pluginName(root, base, overlay, file), file -> key(root, overlay, file)))
				{
					trials.add(loaded.source());
					trialKeys.add(loaded.key());
				}
			}
		}

		List<String> productionOnly = new ArrayList<>();
		for (Map.Entry<String, String> entry : productionNames.entrySet())
		{
			if (!trialKeys.contains(entry.getKey()))
				productionOnly.add(entry.getValue());
		}
		return new LoadedSources(production, trials, productionOnly, homeOnly(plugins), unreachableReferences(root));
	}

	/**
	 * Lists what the user's harness configuration adds to the user's sessions but not to trials.
	 *
	 * @param plugins the campaign's trial plugins with their base copies
	 * @return each hook of the user's hooks file, each plugin that the configuration enables and no trial plugin
	 *   holds, and each MCP server of the configuration, in that order, as a sentence fragment that names it and,
	 *   for a hook or an MCP server, the file that declares it
	 * @throws IOException if a configuration file exists but cannot be read
	 */
	private List<String> homeOnly(List<PluginBase> plugins) throws IOException
	{
		UserConfiguration user = userConfiguration();
		Set<String> entries = new LinkedHashSet<>();
		Optional<JsonNode> content = readHooksFile(user.hooksFile(), JsonMapper.builder().build());
		if (content.isPresent())
		{
			String file = PortablePaths.text(user.hooksFile());
			for (DeclaredHook hook : declaredHooks(content.get().path("hooks")))
			{
				String command = hook.command().map(text -> " `" + text + "`").orElse("");
				entries.add("the " + hook.event() + " hook" + command + " of `" + file + "`");
			}
		}

		Set<String> installed = new HashSet<>();
		Set<String> built = new HashSet<>();
		for (PluginBase base : plugins)
		{
			TrialPlugin plugin = base.plugin();
			if (plugin.isProjectBuilt())
				built.add(plugin.name());
			else
				installed.add(plugin.name());
		}
		for (String plugin : user.plugins())
		{
			int separator = plugin.indexOf('@');
			String withoutMarketplace;
			if (separator < 0)
				withoutMarketplace = plugin;
			else
				withoutMarketplace = plugin.substring(0, separator);
			if (!installed.contains(plugin) && !built.contains(withoutMarketplace))
				entries.add("the plugin `" + plugin + "`");
		}

		String serversFile = PortablePaths.text(user.serversFile());
		for (String server : user.servers())
			entries.add("the MCP server `" + server + "` of `" + serversFile + "`");
		return List.copyOf(entries);
	}

	/**
	 * Reads what the user's harness configuration adds to each of the user's sessions. A file that does not exist or
	 * cannot be parsed adds nothing.
	 *
	 * @return the configuration
	 * @throws IOException if a configuration file exists but cannot be read
	 */
	protected abstract UserConfiguration userConfiguration() throws IOException;

	/**
	 * What the user's harness configuration adds to each of the user's sessions.
	 *
	 * @param hooksFile   the file that declares the user's hooks
	 * @param plugins     the plugins that the configuration enables, each as {@code <plugin>@<marketplace>}, in
	 *                    declaration order
	 * @param serversFile the file that declares the user's MCP servers
	 * @param servers     the names of the MCP servers that the configuration declares and does not disable, in
	 *                    declaration order
	 */
	public record UserConfiguration(Path hooksFile, List<String> plugins, Path serversFile, List<String> servers)
	{
		/**
		 * Creates a configuration.
		 *
		 * @param hooksFile   the file that declares the user's hooks
		 * @param plugins     the plugins that the configuration enables, each as {@code <plugin>@<marketplace>}, in
		 *                    declaration order
		 * @param serversFile the file that declares the user's MCP servers
		 * @param servers     the names of the MCP servers that the configuration declares and does not disable, in
		 *                    declaration order
		 * @throws NullPointerException if any of the arguments are null
		 */
		public UserConfiguration
		{
			requireThat(hooksFile, "hooksFile").isNotNull();
			requireThat(plugins, "plugins").isNotNull();
			requireThat(serversFile, "serversFile").isNotNull();
			requireThat(servers, "servers").isNotNull();
			plugins = List.copyOf(plugins);
			servers = List.copyOf(servers);
		}
	}

	/**
	 * Lists the project files that the project's hooks and MCP servers run.
	 * <p>
	 * A hook's command is split into words at whitespace and shell operators, and an MCP server's program and
	 * arguments are its words. Quotes are removed from each word, and each variable that
	 * {@link #projectDirectoryVariables()} names is replaced by the project directory. A word that then names a
	 * regular file inside the project, relative to the project directory, names a file that the command runs. A word
	 * that holds another variable or starts with {@code ~} names no file, because its value is not known.
	 *
	 * @param project the absolute, normalized project directory
	 * @param listed  the files that are already listed, which are left out
	 * @return the files, in the order that the project's hook files and MCP servers name them, each with the hook's
	 *   event or the server's name
	 * @throws IOException if a hooks file or the MCP configuration exists but cannot be read
	 */
	private List<DerivedSource> commandFiles(Path project, List<DerivedSource> listed) throws IOException
	{
		Set<String> paths = new HashSet<>();
		for (DerivedSource source : listed)
			paths.add(source.path());
		JsonMapper json = JsonMapper.builder().build();
		List<DerivedSource> files = new ArrayList<>();
		for (Path file : projectHookFiles())
		{
			Optional<JsonNode> content = readHooksFile(project.resolve(file), json);
			if (content.isEmpty())
				continue;
			String fileText = PortablePaths.text(file.normalize());
			for (DeclaredHook hook : declaredHooks(content.get().path("hooks")))
			{
				if (hook.command().isEmpty())
					continue;
				String rule = "The " + hook.event() + " hook of `" + fileText + "` runs this file";
				for (String word : COMMAND_SEPARATOR.split(hook.command().get()))
					addCommandFile(project, word, rule, paths, files);
			}
		}
		for (McpServer server : projectMcpServers(project))
		{
			String rule = "The MCP server `" + server.name() + "` of `" + PortablePaths.text(server.file()) +
				"` runs this file";
			for (String word : server.words())
				addCommandFile(project, word, rule, paths, files);
		}
		return files;
	}

	/**
	 * Lists the file that one word of a command names, if it names a project file that is not yet listed.
	 *
	 * @param project the absolute, normalized project directory
	 * @param word    the word, as the command writes it
	 * @param rule    the rule that the derived source records
	 * @param paths   the paths that are already listed, which receives the file's path
	 * @param files   receives the file
	 */
	private void addCommandFile(Path project, String word, String rule, Set<String> paths, List<DerivedSource> files)
	{
		String text = QUOTE.matcher(word).replaceAll("");
		for (String variable : projectDirectoryVariables())
		{
			text = Pattern.compile("\\$(?:\\{" + Pattern.quote(variable) + "\\}|" + Pattern.quote(variable) +
				"(?![A-Za-z0-9_]))").matcher(text).replaceAll(Matcher.quoteReplacement(project.toString()));
		}
		if (text.isEmpty() || text.contains("$") || text.startsWith("~"))
			return;
		Path file;
		try
		{
			file = project.resolve(text).normalize();
		}
		catch (InvalidPathException _)
		{
			// A word that is not a valid path names no file
			return;
		}
		if (!file.startsWith(project) || !Files.isRegularFile(file))
			return;
		String path = PortablePaths.text(project.relativize(file));
		if (paths.add(path))
			files.add(new DerivedSource(path, rule));
	}

	/**
	 * A file that CAT's rules give the primary agent, with the key that matches it across installations.
	 *
	 * @param source the file and the rule that loads it
	 * @param key the file's path relative to the project if it is inside it, or else its path relative to CAT's
	 *   installation
	 */
	private record Loaded(DerivedSource source, String key)
	{
	}

	/**
	 * Lists the files that CAT's rules give the primary agent.
	 *
	 * @param catRoot the absolute CAT installation directory
	 * @param project the absolute, normalized project directory
	 * @param naming names each file as the derived sources list it
	 * @param keying returns the key that matches each file across installations
	 * @return the files, in the order that the rules load them
	 * @throws IOException if a rule cannot be read
	 */
	private List<Loaded> ruleSources(Path catRoot, Path project, Function<Path, String> naming,
		Function<Path, String> keying) throws IOException
	{
		Path userRules = settings.userRules().map(path -> path.toAbsolutePath().normalize()).orElse(null);
		ProjectRuleLoader loader = new ProjectRuleLoader(YAMLMapper.builder().build());
		List<Loaded> sources = new ArrayList<>();
		for (RuleSource source : loader.sourcesForMainAgent(catRoot, userRules, project, harness))
		{
			String referrer = "";
			if (source.referrer().isPresent())
				referrer = naming.apply(source.referrer().get());
			sources.add(new Loaded(new DerivedSource(naming.apply(source.file()), describe(source.kind(), referrer)),
				keying.apply(source.file())));
		}
		return sources;
	}

	/**
	 * Returns the key that matches a loaded file across CAT installations.
	 *
	 * @param project the absolute, normalized project directory
	 * @param installation the absolute, normalized directory of the CAT installation that loads the file
	 * @param file the absolute, normalized file
	 * @return the file's path relative to the project if it is inside it, its path relative to the installation if it
	 *   is inside that, and its absolute path otherwise
	 */
	private static String key(Path project, Path installation, Path file)
	{
		if (file.startsWith(project))
			return PortablePaths.text(project.relativize(file));
		if (file.startsWith(installation))
			return "<cat>/" + PortablePaths.text(installation.relativize(file));
		return file.toString();
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
	 * Names a file of a trial plugin's rule overlay: as the project file that a mapping copies to it, or else by its
	 * absolute path in the plugin's base copy.
	 *
	 * @param project the absolute, normalized project directory
	 * @param base the trial plugin and its base copy
	 * @param overlay the directory that holds the overlaid rules
	 * @param file the absolute, normalized file below {@code overlay}, or a file outside it
	 * @return the name
	 */
	private static String pluginName(Path project, PluginBase base, Path overlay, Path file)
	{
		if (!file.startsWith(overlay))
			return name(project, file);
		Path relative = overlay.relativize(file);
		List<TrialPlugin.Mapping> mappings = base.plugin().mappings();
		for (int i = mappings.size() - 1; i >= 0; --i)
		{
			TrialPlugin.Mapping mapping = mappings.get(i);
			if (!relative.startsWith(mapping.target()) && !mapping.target().toString().isEmpty())
				continue;
			Path source = mapping.source().resolve(mapping.target().relativize(relative));
			if (Files.isRegularFile(project.resolve(source)))
				return PortablePaths.text(source);
		}
		return base.directory().toAbsolutePath().normalize().resolve(relative).toString();
	}

	/**
	 * Copies a trial plugin's rules as trials install them: the base's {@code rules} directory with each mapped
	 * project directory or file written over its mapped path.
	 *
	 * @param project the absolute, normalized project directory
	 * @param base the trial plugin and its base copy
	 * @param overlay the empty directory that receives the rules
	 * @throws IOException if the rules cannot be copied
	 */
	private static void overlayRules(Path project, PluginBase base, Path overlay) throws IOException
	{
		Path rules = Path.of(RULES);
		copyTree(base.directory().resolve(rules), overlay.resolve(rules));
		for (TrialPlugin.Mapping mapping : base.plugin().mappings())
		{
			Path source = project.resolve(mapping.source());
			Path target = mapping.target();
			if (target.startsWith(rules))
			{
				if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
				{
					Path copy = overlay.resolve(target);
					Files.createDirectories(copy.getParent());
					Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
				}
				else
					copyTree(source, overlay.resolve(target));
			}
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
	 * Indicates whether a plugin copy is CAT: whether its manifest states CAT's repository, whatever name the plugin
	 * has.
	 *
	 * @param copy the plugin copy
	 * @return {@code true} if the copy is CAT
	 * @throws IOException if a manifest cannot be read
	 * @see PluginInfo#isCatRepository(String)
	 */
	final boolean isCat(Path copy) throws IOException
	{
		for (Path manifest : PluginManifest.MANIFESTS)
		{
			if (Files.isRegularFile(copy.resolve(manifest)))
				return PluginInfo.isCatRepository(PluginManifest.repository(copy, manifest).orElse(null));
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
	 * Returns the host tools that the port's trials ran, whose versions every trial shares.
	 *
	 * @return the host tools
	 */
	final HostTools hostTools()
	{
		return hostTools;
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
	 * Returns the references in the harness's instruction files to files that trials cannot receive, such as an import
	 * of a missing file.
	 *
	 * @param project the absolute, normalized project root
	 * @return the references, in loading order; this implementation returns none, for a harness whose instruction
	 *   files cannot refer to other files
	 * @throws IOException if an instruction file exists but cannot be read
	 */
	protected List<UnreachableReference> unreachableReferences(Path project) throws IOException
	{
		return List.of();
	}

	/**
	 * Returns the environment variables through which the harness gives a hook or an MCP server the project
	 * directory.
	 *
	 * @return the variables' names; this implementation returns none
	 */
	protected List<String> projectDirectoryVariables()
	{
		return List.of();
	}

	/**
	 * Lists the MCP servers that the project's configuration declares.
	 *
	 * @param project the absolute, normalized project directory
	 * @return the servers that run a program, in declaration order; none if the configuration does not exist or
	 *   cannot be parsed
	 * @throws IOException if the configuration exists but cannot be read
	 */
	protected abstract List<McpServer> projectMcpServers(Path project) throws IOException;

	/**
	 * Lists the MCP servers that one configuration object declares.
	 *
	 * @param file    the configuration file, relative to the project
	 * @param servers the object that maps each server's name to its {@code command} and {@code args}; any other value
	 *                declares no server
	 * @return the servers that name a program, in declaration order, each with the arguments that are strings
	 * @throws NullPointerException if any of the arguments are null
	 */
	protected static List<McpServer> mcpServers(Path file, JsonNode servers)
	{
		requireThat(file, "file").isNotNull();
		requireThat(servers, "servers").isNotNull();
		List<McpServer> declared = new ArrayList<>();
		if (!servers.isObject())
			return declared;
		for (String name : servers.propertyNames())
		{
			JsonNode server = servers.get(name);
			JsonNode command = server.path("command");
			if (name.isBlank() || !command.isString())
				continue;
			List<String> words = new ArrayList<>();
			words.add(command.asString());
			for (JsonNode argument : server.path("args"))
			{
				if (argument.isString())
					words.add(argument.asString());
			}
			declared.add(new McpServer(file, name, words));
		}
		return declared;
	}

	/**
	 * Returns the runner options that pass a request's read-only evidence.
	 *
	 * @param evidence the paths that the session may read but not change
	 * @return the options, empty if the runner has no option for read-only evidence
	 */
	protected abstract List<String> readOnlyArguments(List<Path> evidence);

	/**
	 * Returns the runner options of a trial that runs on the control source, where the target prompt file is empty.
	 *
	 * @return the options that keep the harness from loading another instruction file in place of the emptied one,
	 *   empty if the harness loads none
	 */
	protected abstract List<String> controlSourceArguments();

	/**
	 * {@inheritDoc}
	 * <p>
	 * The registrations are those of the project's hook files, in the order that {@link #projectHookFiles()} lists
	 * them, followed by those of each trial plugin in turn, in the order that
	 * {@link #pluginHooks(PluginFiles, JsonMapper)} lists them. A plugin's hooks file that a mapping copies from the
	 * project is named as the project's file. A file that does not exist or is not valid JSON registers no hook.
	 */
	@Override
	public final List<HookRegistration> hookRegistrations(Path project, List<PluginBase> plugins) throws IOException
	{
		requireThat(project, "project").isNotNull();
		requireThat(plugins, "plugins").isNotNull();
		JsonMapper json = JsonMapper.builder().build();
		List<HookRegistration> registrations = new ArrayList<>();
		for (Path file : projectHookFiles())
		{
			Optional<JsonNode> content = readHooksFile(project.resolve(file), json);
			if (content.isPresent())
				addRegistrations("", file, content.get().path("hooks"), registrations);
		}
		for (PluginBase base : plugins)
		{
			PluginFiles files = new PluginFiles(project, base);
			for (HookDeclaration declaration : pluginHooks(files, json))
			{
				Optional<Path> projectFile = files.projectFile(declaration.file());
				if (projectFile.isPresent())
					addRegistrations("", projectFile.get(), declaration.hooks(), registrations);
				else
					addRegistrations(base.plugin().name(), declaration.file(), declaration.hooks(), registrations);
			}
		}
		return registrations;
	}

	/**
	 * Adds the hooks that one file declares.
	 *
	 * @param plugin        the name of the trial plugin whose file declares the hooks, or an empty string for a file
	 *                      of the project
	 * @param file          the file, relative to the plugin directory or to the project
	 * @param hooks         the object that maps each event to its matcher groups; any other value declares no hook
	 * @param registrations receives each hook in declaration order
	 */
	private static void addRegistrations(String plugin, Path file, JsonNode hooks,
		List<HookRegistration> registrations)
	{
		String fileText = PortablePaths.text(file.normalize());
		for (DeclaredHook hook : declaredHooks(hooks))
		{
			registrations.add(new HookRegistration(plugin, fileText, hook.event(), hook.matcher(),
				hook.command().orElse(hook.type())));
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The hooks are the command hooks of the project's hook files, in the order that {@link #projectHookFiles()} lists
	 * them, followed by those of each trial plugin in turn, that run when a new session starts or when the user
	 * submits a prompt. A hook's input holds no transcript, since none exists before the model acts.
	 */
	@Override
	public final List<HookInvocation> earlyHooks(Path project, List<PluginBase> plugins, Optional<Path> home,
		String prompt) throws IOException
	{
		requireThat(project, "project").isNotNull();
		requireThat(plugins, "plugins").isNotNull();
		requireThat(home, "home").isNotNull();
		requireThat(prompt, "prompt").isNotNull();
		JsonMapper json = JsonMapper.builder().build();
		Map<String, String> environment = new LinkedHashMap<>();
		for (String variable : projectDirectoryVariables())
			environment.put(variable, project.toString());
		if (home.isPresent())
		{
			HarnessEnvironment.setHomeDirectory(environment, home.get());
			environment.putAll(harnessHome(home.get()));
		}
		List<HookInvocation> invocations = new ArrayList<>();
		for (Path file : projectHookFiles())
		{
			Optional<JsonNode> content = readHooksFile(project.resolve(file), json);
			if (content.isPresent())
			{
				addEarlyHooks("`" + PortablePaths.text(file.normalize()) + "`", content.get().path("hooks"),
					environment, project, prompt, json, invocations);
			}
		}
		for (PluginBase base : plugins)
		{
			Map<String, String> pluginEnvironment = new LinkedHashMap<>(environment);
			pluginEnvironment.put(pluginRootVariable(), base.directory().toString());
			for (HookDeclaration declaration : pluginHooks(new PluginFiles(project, base), json))
			{
				addEarlyHooks("`" + PortablePaths.text(declaration.file().normalize()) + "` in the plugin `" +
					base.plugin().name() + "`", declaration.hooks(), pluginEnvironment, project, prompt, json,
					invocations);
			}
		}
		return invocations;
	}

	/**
	 * Adds the command hooks of one file that run before the model acts.
	 *
	 * @param file        names the file that declares the hooks
	 * @param hooks       the object that maps each event to its matcher groups
	 * @param environment the variables that the harness sets for the hooks
	 * @param project     the directory that the session runs in
	 * @param prompt      the prompt that the session submits
	 * @param json        the JSON mapper
	 * @param invocations receives each hook in declaration order
	 */
	private void addEarlyHooks(String file, JsonNode hooks, Map<String, String> environment, Path project,
		String prompt, JsonMapper json, List<HookInvocation> invocations)
	{
		for (DeclaredHook hook : declaredHooks(hooks))
		{
			if (!hook.type().equals("command") || hook.command().isEmpty() || !runsBeforeModel(hook))
				continue;
			Optional<List<String>> process = hookProcess(hook.declaration());
			if (process.isEmpty())
				continue;
			ObjectNode input = json.createObjectNode();
			input.put("session_id", HOOK_SESSION_ID);
			input.put("cwd", project.toString());
			input.put("hook_event_name", hook.event());
			input.put("permission_mode", "default");
			if (hook.event().equals(SESSION_START))
				input.put("source", STARTUP);
			else
				input.put("prompt", prompt);
			Duration timeout = HOOK_TIMEOUT;
			JsonNode seconds = hook.declaration().path("timeout");
			if (seconds.isIntegralNumber() && seconds.asLong() > 0)
				timeout = Duration.ofSeconds(seconds.asLong());
			invocations.add(new HookInvocation("the " + hook.event() + " hook `" + hook.command().get() + "` of " +
				file, process.get(), environment, json.writeValueAsString(input), timeout));
		}
	}

	/**
	 * Returns whether a hook runs before the model acts in a new session: at its start, or when the user submits a
	 * prompt.
	 *
	 * @param hook the hook
	 * @return {@code true} if it runs
	 */
	private static boolean runsBeforeModel(DeclaredHook hook)
	{
		return switch (hook.event())
		{
			case PROMPT_SUBMIT -> true;
			case SESSION_START ->
			{
				String matcher = hook.matcher();
				if (matcher.isEmpty() || matcher.equals("*"))
					yield true;
				try
				{
					yield Pattern.compile(matcher).matcher(STARTUP).matches();
				}
				catch (PatternSyntaxException _)
				{
					yield matcher.equals(STARTUP);
				}
			}
			default -> false;
		};
	}

	/**
	 * Returns how the harness runs a command hook.
	 *
	 * @param hook the hook as its file declares it, with its {@code command} and any key of the harness
	 * @return the program and its arguments, or empty if the harness does not run the hook on this platform
	 */
	protected abstract Optional<List<String>> hookProcess(JsonNode hook);

	/**
	 * Returns the environment variable through which the harness gives a plugin's hook the plugin's directory.
	 *
	 * @return the variable's name
	 */
	protected abstract String pluginRootVariable();

	/**
	 * Returns the variables that make the harness use the empty harness home in a process home.
	 *
	 * @param home the process home
	 * @return the variables
	 */
	protected abstract Map<String, String> harnessHome(Path home);

	/**
	 * Lists the hooks that one file declares.
	 *
	 * @param hooks the object that maps each event to its matcher groups; any other value declares no hook
	 * @return the hooks, in declaration order
	 */
	private static List<DeclaredHook> declaredHooks(JsonNode hooks)
	{
		List<DeclaredHook> declared = new ArrayList<>();
		if (!hooks.isObject())
			return declared;
		for (String event : hooks.propertyNames())
		{
			JsonNode groups = hooks.get(event);
			if (!groups.isArray())
				continue;
			for (JsonNode group : groups)
			{
				JsonNode matcherNode = group.path("matcher");
				String matcher;
				if (matcherNode.isString())
					matcher = matcherNode.asString();
				else
					matcher = "";
				for (JsonNode hook : group.path("hooks"))
				{
					JsonNode command = hook.path("command");
					Optional<String> text;
					if (command.isString())
						text = Optional.of(command.asString());
					else
						text = Optional.empty();
					declared.add(new DeclaredHook(event, matcher, hook.path("type").asString(""), text, hook));
				}
			}
		}
		return declared;
	}

	/**
	 * A hook as a hooks file declares it.
	 *
	 * @param event       the event that runs the hook
	 * @param matcher     the matcher that selects the event's occurrences, or an empty string if the hook has none
	 * @param type        the hook's type, or an empty string if it declares none
	 * @param command     the command that the hook runs, or empty if it runs none
	 * @param declaration the hook's object in the file
	 */
	private record DeclaredHook(String event, String matcher, String type, Optional<String> command,
		JsonNode declaration)
	{
	}

	/**
	 * Returns the project files whose hooks the harness registers.
	 *
	 * @return the files, relative to the project
	 */
	protected abstract List<Path> projectHookFiles();

	/**
	 * Returns the hook declarations of a trial plugin.
	 *
	 * @param plugin the plugin's files as trials install them
	 * @param json   the JSON mapper
	 * @return the declarations, in the order that the harness registers them
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a file exists but cannot be read
	 */
	protected abstract List<HookDeclaration> pluginHooks(PluginFiles plugin, JsonMapper json) throws IOException;

	/**
	 * Reads a hooks file.
	 *
	 * @param file the file
	 * @param json the JSON mapper
	 * @return the file's JSON value, or empty if the file is not a regular file or is not valid JSON
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file exists but cannot be read
	 */
	protected static Optional<JsonNode> readHooksFile(Path file, JsonMapper json) throws IOException
	{
		requireThat(file, "file").isNotNull();
		requireThat(json, "json").isNotNull();
		if (!Files.isRegularFile(file))
			return Optional.empty();
		String text = Files.readString(file);
		try
		{
			return Optional.of(json.readTree(text));
		}
		catch (JacksonException _)
		{
			// The harness ignores a hooks file that it cannot parse
			return Optional.empty();
		}
	}

	/**
	 * The hooks that one file declares.
	 *
	 * @param file  the file that declares the hooks, relative to the project or to the plugin directory
	 * @param hooks the object that maps each event to its matcher groups
	 */
	public record HookDeclaration(Path file, JsonNode hooks)
	{
		/**
		 * Creates a hook declaration.
		 *
		 * @param file  the file that declares the hooks, relative to the project or to the plugin directory
		 * @param hooks the object that maps each event to its matcher groups
		 * @throws NullPointerException if any of the arguments are null
		 */
		public HookDeclaration
		{
			requireThat(file, "file").isNotNull();
			requireThat(hooks, "hooks").isNotNull();
		}
	}

	/**
	 * An MCP server that the project's configuration declares.
	 *
	 * @param file  the configuration file, relative to the project
	 * @param name  the server's name
	 * @param words the program that the server runs, followed by its arguments
	 */
	public record McpServer(Path file, String name, List<String> words)
	{
		/**
		 * Creates an MCP server.
		 *
		 * @param file  the configuration file, relative to the project
		 * @param name  the server's name
		 * @param words the program that the server runs, followed by its arguments
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code name} is blank
		 */
		public McpServer
		{
			requireThat(file, "file").isNotNull();
			requireThat(name, "name").isNotBlank();
			requireThat(words, "words").isNotNull();
			words = List.copyOf(words);
		}
	}

	/**
	 * How a harness runner is configured. Every session of a port shares these settings; the model and reasoning
	 * effort come from each request's profile.
	 *
	 * @param artifact the complete harness artifact that the runner validates and installs
	 * @param runRoot the absolute directory that holds the run directories of sessions of the development part
	 * @param heldOut lists, when each session starts, the campaigns' held-out directories
	 * @param executable the harness executable, or blank for the harness's default command
	 * @param installCat indicates whether the user's sessions of the harness load CAT from the artifact, so that the
	 *   files that load in production include CAT's rules; trials install CAT only when a trial plugin is CAT
	 * @param environment the host environment that the runner scrubs for the session
	 * @param configurationDirectory the host's harness configuration directory, which holds the login
	 * @param capacity the shared capacity from which every session takes one permit
	 * @param clock the clock for time limits
	 * @param replacer replaces the files of the sessions' runs
	 * @param userRules the directory of the user's CAT rules, which trials that install CAT load, or empty if they load
	 *   none
	 * @param hostProject the project of the host session that starts the sessions, whose files no session may read
	 *   except those of its own run and the evidence that it reads, or {@code null} to deny no project's files
	 */
	public record Settings(Path artifact, Path runRoot, HeldOutPaths heldOut, String executable, boolean installCat,
		Map<String, String> environment, Path configurationDirectory, HarnessCapacity capacity, Clock clock,
		FileReplacer replacer, Optional<Path> userRules, Path hostProject)
	{
		/**
		 * Creates settings.
		 *
		 * @param artifact the complete harness artifact that the runner validates and installs
		 * @param runRoot the absolute directory that holds the run directories of sessions of the development part
		 * @param heldOut lists, when each session starts, the campaigns' held-out directories
		 * @param executable the harness executable, or blank for the harness's default command
		 * @param installCat whether the user's sessions of the harness load CAT from the artifact, so that the files
		 *   that load in production include CAT's rules; trials install CAT only when a trial plugin is CAT
		 * @param environment the host environment that the runner scrubs for the session
		 * @param configurationDirectory the host's harness configuration directory, which holds the login
		 * @param capacity the shared capacity from which every session takes one permit
		 * @param clock the clock for time limits
		 * @param replacer replaces the files of the sessions' runs
		 * @param userRules the directory of the user's CAT rules, which trials that install CAT load, or empty if they
		 *   load none
		 * @param hostProject the project of the host session that starts the sessions, whose files no session may
		 *   read except those of its own run and the evidence that it reads, or {@code null} to deny no project's
		 *   files
		 * @throws NullPointerException if any argument other than {@code hostProject} is null
		 * @throws IllegalArgumentException if {@code runRoot} or {@code hostProject} are not absolute, or
		 *   {@code runRoot} is a file system root
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
			requireThat(replacer, "replacer").isNotNull();
			requireThat(userRules, "userRules").isNotNull();
			runRoot = runRoot.normalize();
			requireThat(runRoot.getFileName(), "runRoot.getFileName()").isNotNull();
			if (hostProject != null)
			{
				requireThat(hostProject.isAbsolute(), "hostProject.isAbsolute").isTrue();
				hostProject = hostProject.normalize();
			}
			environment = Map.copyOf(environment);
		}

		/**
		 * Creates settings that deny no project's files.
		 *
		 * @param artifact the complete harness artifact that the runner validates and installs
		 * @param runRoot the absolute directory that holds the run directories of sessions of the development part
		 * @param heldOut lists, when each session starts, the campaigns' held-out directories
		 * @param executable the harness executable, or blank for the harness's default command
		 * @param installCat whether the user's sessions of the harness load CAT from the artifact, so that the files
		 *   that load in production include CAT's rules; trials install CAT only when a trial plugin is CAT
		 * @param environment the host environment that the runner scrubs for the session
		 * @param configurationDirectory the host's harness configuration directory, which holds the login
		 * @param capacity the shared capacity from which every session takes one permit
		 * @param clock the clock for time limits
		 * @param replacer replaces the files of the sessions' runs
		 * @param userRules the directory of the user's CAT rules, which trials that install CAT load, or empty if they
		 *   load none
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code runRoot} is not absolute or is a file system root
		 */
		public Settings(Path artifact, Path runRoot, HeldOutPaths heldOut, String executable, boolean installCat,
			Map<String, String> environment, Path configurationDirectory, HarnessCapacity capacity, Clock clock,
			FileReplacer replacer, Optional<Path> userRules)
		{
			this(artifact, runRoot, heldOut, executable, installCat, environment, configurationDirectory, capacity,
				clock, replacer, userRules, null);
		}

		/**
		 * Creates settings whose trials load no user rules and that deny no project's files.
		 *
		 * @param artifact the complete harness artifact that the runner validates and installs
		 * @param runRoot the absolute directory that holds the run directories of sessions of the development part
		 * @param heldOut lists, when each session starts, the campaigns' held-out directories
		 * @param executable the harness executable, or blank for the harness's default command
		 * @param installCat indicates whether the user's sessions of the harness load CAT from the artifact, so that
		 *   the files that load in production include CAT's rules; trials install CAT only when a trial plugin is CAT
		 * @param environment the host environment that the runner scrubs for the session
		 * @param configurationDirectory the host's harness configuration directory, which holds the login
		 * @param capacity the shared capacity from which every session takes one permit
		 * @param clock the clock for time limits
		 * @param replacer replaces the files of the sessions' runs
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code runRoot} is not absolute or is a file system root
		 */
		public Settings(Path artifact, Path runRoot, HeldOutPaths heldOut, String executable, boolean installCat,
			Map<String, String> environment, Path configurationDirectory, HarnessCapacity capacity, Clock clock,
			FileReplacer replacer)
		{
			this(artifact, runRoot, heldOut, executable, installCat, environment, configurationDirectory, capacity,
				clock, replacer, Optional.empty());
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
