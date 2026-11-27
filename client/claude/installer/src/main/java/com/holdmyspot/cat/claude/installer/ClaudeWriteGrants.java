package com.holdmyspot.cat.claude.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SandboxWrites;
import com.holdmyspot.cat.common.WriteGrant;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads where Claude Code's sandbox lets commands write, or may let them write, from the settings that apply to a
 * session.
 * <p>
 * The reading overestimates. A grant that the settings give in a form that CAT interprets for certain is a certain
 * grant. A grant that CAT cannot interpret for certain, or whose settings may not apply to the session, is a possible
 * grant. A denial counts only when CAT is certain of it. What CAT cannot read is listed as a problem.
 */
public final class ClaudeWriteGrants
{
	/**
	 * The folder of the managed settings on Linux.
	 */
	public static final Path LINUX_MANAGED_DIRECTORY = Path.of("/etc/claude-code");
	/**
	 * The folder of the managed settings on macOS.
	 */
	public static final Path MACOS_MANAGED_DIRECTORY = Path.of("/Library/Application Support/ClaudeCode");
	/**
	 * The folder of the managed settings on native Windows.
	 */
	public static final Path WINDOWS_MANAGED_DIRECTORY = Path.of("C:\\Program Files\\ClaudeCode");
	/**
	 * The folder of the Windows managed settings, which Claude Code also reads on WSL.
	 */
	public static final Path WSL_WINDOWS_MANAGED_DIRECTORY = Path.of("/mnt/c/Program Files/ClaudeCode");
	/**
	 * The managed settings file, inside a managed settings folder.
	 */
	public static final String MANAGED_FILE = "managed-settings.json";
	/**
	 * The folder of managed settings drop-in files, inside a managed settings folder.
	 */
	public static final String MANAGED_DROP_INS = "managed-settings.d";
	/**
	 * The macOS managed preferences that may hold managed settings, as {@code %s} for the user's name. CAT cannot
	 * read them.
	 */
	public static final List<String> MACOS_MANAGED_PREFERENCES = List.of(
		"/Library/Managed Preferences/%s/com.anthropic.claudecode.plist",
		"/Library/Managed Preferences/com.anthropic.claudecode.plist");
	/**
	 * The copy of the settings that the user's organization manages from its server, inside Claude Code's
	 * configuration folder.
	 */
	public static final String REMOTE_SETTINGS = "remote-settings.json";
	/**
	 * The folders that the sandbox lets commands write without any setting, other than the working directories.
	 */
	public static final List<Path> BUILT_IN_WRITES = List.of(Path.of("/tmp/claude").toAbsolutePath(),
		Path.of("/private/tmp/claude").toAbsolutePath());
	/**
	 * The paths inside each folder that commands may write that the sandbox keeps them from writing.
	 */
	public static final List<String> BUILT_IN_WRITE_DENIALS = List.of(".claude", ".git/hooks", ".git/config");
	/**
	 * The environment variable that names the temporary folder that Claude Code gives commands, which the sandbox
	 * lets them write.
	 */
	public static final String TEMPORARY_DIRECTORY_VARIABLE = "CLAUDE_CODE_TMPDIR";
	/**
	 * The settings key that makes Claude Code on WSL apply the Windows policy chain, including registry policies that
	 * CAT cannot read.
	 */
	public static final String WSL_INHERITS_WINDOWS_SETTINGS = "wslInheritsWindowsSettings";
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final Pattern RULE = Pattern.compile("([A-Za-z][A-Za-z0-9_]*)(?:\\((.*)\\))?");
	private static final String GLOB_CHARACTERS = "*?[{";
	private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^(?:[A-Za-z]:[\\\\/]|\\\\\\\\[^\\\\])");
	private static final Pattern RULE_DRIVE = Pattern.compile("^/([A-Za-z])(/.*)?$");

	/**
	 * Prevents instantiation.
	 */
	private ClaudeWriteGrants()
	{
	}

	/**
	 * The options of the command line that start a session and that decide which settings apply to it.
	 *
	 * @param addDirectories the values of {@code --add-dir}, in order
	 * @param settings       the value of {@code --settings}, which is JSON or the path of a settings file, or empty if
	 *                       the option is absent
	 * @param settingSources the names of the scopes that {@code --setting-sources} lists ({@code user},
	 *                       {@code project} and {@code local}), or empty if the option is absent
	 * @param deniedTools    the permission rules that {@code --disallowedTools} lists, in order
	 * @param restricted     {@code true} if {@code --restricted} is present, which loads no user, project or local
	 *                       settings
	 */
	public record Launch(List<String> addDirectories, Optional<String> settings, Optional<Set<String>> settingSources,
		List<String> deniedTools, boolean restricted)
	{
		/**
		 * Creates the options.
		 *
		 * @param addDirectories the values of {@code --add-dir}, in order
		 * @param settings       the value of {@code --settings}, or empty if the option is absent
		 * @param settingSources the scopes that {@code --setting-sources} lists, or empty if the option is absent
		 * @param deniedTools    the permission rules that {@code --disallowedTools} lists, in order
		 * @param restricted     {@code true} if {@code --restricted} is present
		 * @throws NullPointerException if any argument is null
		 */
		public Launch
		{
			addDirectories = List.copyOf(addDirectories);
			requireThat(settings, "settings").isNotNull();
			requireThat(settingSources, "settingSources").isNotNull();
			settingSources = settingSources.map(Set::copyOf);
			deniedTools = List.copyOf(deniedTools);
		}
	}

	/**
	 * What is known of the session.
	 *
	 * @param userHome                the user's absolute home folder
	 * @param configurationDirectory  the absolute folder that holds the user's settings
	 * @param projectDirectories      the absolute project folders whose settings may apply: the session's project
	 *                                folder alone when it is known, otherwise every folder that may be the project
	 * @param projectKnown            {@code true} if the first of {@code projectDirectories} is the session's project
	 *                                folder
	 * @param temporaryDirectory      the temporary folder that Claude Code gives commands, if one is named
	 * @param managedDirectories      the folders that may hold managed settings
	 * @param unreadableManagedSources the managed sources that CAT cannot read, which count as a problem if they exist
	 * @param launch                  the command line options, or empty if the command line is not known
	 */
	public record Session(Path userHome, Path configurationDirectory, List<Path> projectDirectories,
		boolean projectKnown, Optional<Path> temporaryDirectory, List<Path> managedDirectories,
		List<Path> unreadableManagedSources, Optional<Launch> launch)
	{
		/**
		 * Creates the description.
		 *
		 * @param userHome                the user's absolute home folder
		 * @param configurationDirectory  the absolute folder that holds the user's settings
		 * @param projectDirectories      the absolute project folders whose settings may apply
		 * @param projectKnown            {@code true} if the first of {@code projectDirectories} is the session's
		 *                                project folder
		 * @param temporaryDirectory      the temporary folder that Claude Code gives commands, if one is named
		 * @param managedDirectories      the folders that may hold managed settings
		 * @param unreadableManagedSources the managed sources that CAT cannot read
		 * @param launch                  the command line options, or empty if the command line is not known
		 * @throws NullPointerException     if any argument is null
		 * @throws IllegalArgumentException if a path is relative
		 */
		public Session
		{
			requireThat(userHome, "userHome").isAbsolute();
			requireThat(configurationDirectory, "configurationDirectory").isAbsolute();
			projectDirectories = List.copyOf(projectDirectories);
			requireThat(temporaryDirectory, "temporaryDirectory").isNotNull();
			managedDirectories = List.copyOf(managedDirectories);
			unreadableManagedSources = List.copyOf(unreadableManagedSources);
			requireThat(launch, "launch").isNotNull();
			for (Path project : projectDirectories)
				requireThat(project, "project").isAbsolute();
			temporaryDirectory.ifPresent(path -> requireThat(path, "temporaryDirectory").isAbsolute());
			for (Path directory : managedDirectories)
				requireThat(directory, "managedDirectory").isAbsolute();
			for (Path source : unreadableManagedSources)
				requireThat(source, "unreadableManagedSource").isAbsolute();
		}
	}

	/**
	 * Returns the folders that may hold Claude Code's managed settings on a platform.
	 *
	 * @param platform the platform that Claude Code runs on
	 * @return the folders, leaving out those that are not absolute paths on this machine's filesystem
	 * @throws NullPointerException if {@code platform} is null
	 */
	public static List<Path> managedDirectories(Platform platform)
	{
		requireThat(platform, "platform").isNotNull();
		List<Path> directories;
		if (platform.isMacos())
			directories = List.of(MACOS_MANAGED_DIRECTORY);
		else if (platform.isWindows())
			directories = List.of(WINDOWS_MANAGED_DIRECTORY);
		else
			directories = List.of(LINUX_MANAGED_DIRECTORY, WSL_WINDOWS_MANAGED_DIRECTORY);
		return directories.stream().filter(Path::isAbsolute).toList();
	}

	/**
	 * Returns the managed sources of a platform that CAT cannot read.
	 *
	 * @param platform the platform that Claude Code runs on
	 * @param userName the user's login name
	 * @return the sources, which count as a problem if they exist
	 * @throws NullPointerException if any argument is null
	 */
	public static List<Path> unreadableManagedSources(Platform platform, String userName)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(userName, "userName").isNotNull();
		if (!platform.isMacos())
			return List.of();
		List<Path> sources = new ArrayList<>();
		for (String preference : MACOS_MANAGED_PREFERENCES)
			sources.add(Path.of(preference.formatted(userName)));
		return sources;
	}

	/**
	 * Reads the sandbox's writes.
	 *
	 * @param session what is known of the session
	 * @return the grants, the certain denials and what could not be read
	 * @throws NullPointerException if {@code session} is null
	 */
	public static SandboxWrites read(Session session)
	{
		requireThat(session, "session").isNotNull();
		return new Reading(session).read();
	}

	/**
	 * The scope of a settings layer.
	 */
	private enum Scope
	{
		/**
		 * Settings that an administrator manages.
		 */
		MANAGED,
		/**
		 * Settings that the command line gives.
		 */
		FLAG,
		/**
		 * The project's local settings.
		 */
		LOCAL,
		/**
		 * The project's shared settings.
		 */
		PROJECT,
		/**
		 * The user's settings.
		 */
		USER
	}

	/**
	 * The syntax that a path of the settings uses.
	 */
	private enum Syntax
	{
		/**
		 * The syntax of the sandbox's file settings and of {@code permissions.additionalDirectories}.
		 */
		SANDBOX,
		/**
		 * The syntax of the specifier of a permission rule.
		 */
		RULE
	}

	/**
	 * A settings layer.
	 *
	 * @param scope    the scope of the settings
	 * @param source   the source as the user knows it
	 * @param settings the settings
	 * @param root     the folder that relative paths of the settings are relative to
	 * @param certain  {@code true} if CAT is certain that the settings apply to the session
	 */
	private record Layer(Scope scope, String source, JsonNode settings, Path root, boolean certain)
	{
	}

	/**
	 * A folder that a settings path may name.
	 *
	 * @param path    the folder
	 * @param certain {@code true} if the settings name this folder and no other
	 */
	private record Named(Path path, boolean certain)
	{
	}

	/**
	 * The folders that a spelled path may name.
	 *
	 * @param paths the spelled folders, or empty if the path names another user's home
	 * @param exact {@code true} if the release reads the path in this one way
	 */
	private record Candidates(List<String> paths, boolean exact)
	{
	}

	/**
	 * One reading of a session's settings.
	 */
	private static final class Reading
	{
		private final Session session;
		private final boolean windows;
		private final Set<String> problems = new LinkedHashSet<>();
		private final List<Layer> layers = new ArrayList<>();
		private final List<WriteGrant> grants = new ArrayList<>();
		private final List<Path> denials = new ArrayList<>();
		private boolean uncertainEverything;
		private boolean projectRestricted;
		private boolean managedRulesOnly;

		/**
		 * Creates a reading.
		 *
		 * @param session what is known of the session
		 */
		private Reading(Session session)
		{
			this.session = session;
			this.windows = session.userHome().getFileSystem().getSeparator().equals("\\");
		}

		/**
		 * Reads the settings.
		 *
		 * @return the grants, the certain denials and the problems
		 */
		private SandboxWrites read()
		{
			for (Path source : session.unreadableManagedSources())
			{
				if (Files.exists(source))
				{
					problems.add("the managed settings in " + source + " are in a form that CAT cannot read, so " +
						"CAT does not know what they grant");
				}
			}
			collectLayers();
			for (Layer layer : layers)
			{
				if (bool(layer, WSL_INHERITS_WINDOWS_SETTINGS).orElse(false))
				{
					problems.add(layer.source() + " sets " + WSL_INHERITS_WINDOWS_SETTINGS + ", which applies Windows " +
						"registry policies that CAT cannot read, so they may grant writes that CAT does not know");
				}
			}
			boolean enabled = false;
			boolean disabled = false;
			boolean certainlyEnabled = false;
			for (Layer layer : layers)
			{
				Optional<Boolean> value = bool(layer, "sandbox", "enabled");
				if (value.isEmpty())
					continue;
				if (value.get())
				{
					enabled = true;
					if (layer.certain())
						certainlyEnabled = true;
				}
				else
					disabled = true;
			}
			managedRulesOnly = anyTrue(Scope.MANAGED, "allowManagedPermissionRulesOnly") ||
				anyTrue(Scope.MANAGED, "sandbox", "network", "allowManagedDomainsOnly");
			projectRestricted = anyTrue(Scope.MANAGED, "sandbox", "network", "allowManagedDomainsOnly") ||
				anyFalse(Scope.MANAGED, "sandbox", "allowUnsandboxedCommands") ||
				anyFalse(Scope.FLAG, "sandbox", "allowUnsandboxedCommands");
			if (!enabled)
				return new SandboxWrites(List.of(), List.of(), List.copyOf(problems));
			uncertainEverything = disabled || !certainlyEnabled;
			addGrants();
			addDenials();
			return new SandboxWrites(distinct(grants), List.copyOf(new LinkedHashSet<>(denials)),
				List.copyOf(problems));
		}

		/**
		 * Collects the layers whose settings may apply to the session.
		 */
		private void collectLayers()
		{
			List<List<Layer>> managed = new ArrayList<>();
			for (Path directory : session.managedDirectories())
			{
				List<Layer> group = managedLayers(directory);
				if (!group.isEmpty())
					managed.add(group);
			}
			Path remote = session.configurationDirectory().resolve(REMOTE_SETTINGS);
			if (Files.exists(remote))
			{
				List<Layer> group = new ArrayList<>();
				fileLayer(Scope.MANAGED, remote, session.configurationDirectory(), true, group);
				if (!group.isEmpty())
					managed.add(group);
			}
			// Which managed source wins when several exist is not certain
			boolean managedCertain = managed.size() <= 1;
			for (List<Layer> group : managed)
			{
				for (Layer layer : group)
					layers.add(new Layer(layer.scope(), layer.source(), layer.settings(), layer.root(), managedCertain));
			}
			Optional<Launch> launch = session.launch();
			boolean projectCertain = session.projectKnown() && launch.isPresent();
			if (launch.isPresent() && launch.get().settings().isPresent())
				flagLayers(launch.get().settings().get(), projectCertain);
			if (include("local"))
			{
				for (Path project : session.projectDirectories())
				{
					fileLayer(Scope.LOCAL, project.resolve(".claude").resolve("settings.local.json"), project,
						projectCertain && project.equals(session.projectDirectories().getFirst()), layers);
				}
			}
			if (include("project"))
			{
				for (Path project : session.projectDirectories())
				{
					fileLayer(Scope.PROJECT, project.resolve(".claude").resolve("settings.json"), project,
						projectCertain && project.equals(session.projectDirectories().getFirst()), layers);
				}
			}
			if (include("user"))
			{
				fileLayer(Scope.USER, session.configurationDirectory().resolve("settings.json"),
					session.configurationDirectory(), true, layers);
			}
		}

		/**
		 * Indicates whether the session may load the settings of a scope.
		 *
		 * @param scope the name that {@code --setting-sources} gives the scope
		 * @return {@code false} only if the command line is known and leaves the scope out
		 */
		private boolean include(String scope)
		{
			if (session.launch().isEmpty())
				return true;
			Launch launch = session.launch().get();
			return !launch.restricted() && launch.settingSources().map(sources -> sources.contains(scope)).orElse(true);
		}

		/**
		 * Adds the layers that the command line option {@code --settings} gives.
		 *
		 * @param value   the option's value
		 * @param certain {@code true} if CAT is certain of the project folder that a relative path names
		 */
		private void flagLayers(String value, boolean certain)
		{
			if (value.strip().startsWith("{"))
			{
				String source = "the settings of Claude Code's option --settings";
				JsonNode settings = parse(value, source);
				if (settings != null)
				{
					Path root = session.userHome();
					if (!session.projectDirectories().isEmpty())
						root = session.projectDirectories().getFirst();
					layers.add(new Layer(Scope.FLAG, source, settings, root, true));
				}
				return;
			}
			List<Path> files = new ArrayList<>();
			try
			{
				Path given = Path.of(value);
				if (given.isAbsolute())
					files.add(given.normalize());
				else
				{
					for (Path project : session.projectDirectories())
						files.add(project.resolve(given).normalize());
				}
			}
			catch (InvalidPathException _)
			{
				problems.add("CAT cannot read the settings file of Claude Code's option --settings: " + value +
					" is not a path");
				return;
			}
			boolean found = false;
			for (Path file : files)
			{
				if (Files.exists(file))
				{
					found = true;
					fileLayer(Scope.FLAG, file, file.getParent(), certain || Path.of(value).isAbsolute(), layers);
				}
			}
			if (!found)
			{
				problems.add("CAT cannot find the settings file " + value + " of Claude Code's option --settings, so " +
					"it does not know what that file grants");
			}
		}

		/**
		 * Returns the layers of a managed settings folder.
		 *
		 * @param directory the folder
		 * @return the layers: the drop-in files, then the managed settings file
		 */
		private List<Layer> managedLayers(Path directory)
		{
			List<Layer> group = new ArrayList<>();
			Path dropIns = directory.resolve(MANAGED_DROP_INS);
			if (Files.isDirectory(dropIns))
			{
				List<Path> files = new ArrayList<>();
				try (DirectoryStream<Path> stream = Files.newDirectoryStream(dropIns, "*.json"))
				{
					for (Path file : stream)
						files.add(file);
				}
				catch (IOException e)
				{
					problems.add("CAT cannot list the managed settings in " + dropIns + ": " + e.getMessage());
				}
				files.sort(Comparator.comparing((Path file) -> file.getFileName().toString()));
				for (Path file : files)
					fileLayer(Scope.MANAGED, file, directory, true, group);
			}
			Path file = directory.resolve(MANAGED_FILE);
			if (Files.exists(file))
				fileLayer(Scope.MANAGED, file, directory, true, group);
			return group;
		}

		/**
		 * Reads a settings file into a layer, if it exists.
		 *
		 * @param scope   the scope of the settings
		 * @param file    the file
		 * @param root    the folder that relative paths of the settings are relative to
		 * @param certain {@code true} if CAT is certain that the settings apply to the session
		 * @param into    the list that receives the layer
		 */
		private void fileLayer(Scope scope, Path file, Path root, boolean certain, List<Layer> into)
		{
			if (!Files.exists(file))
				return;
			String text;
			try
			{
				text = Files.readString(file);
			}
			catch (IOException e)
			{
				problems.add("CAT cannot read the settings file " + file + ": " + e.getMessage());
				return;
			}
			JsonNode settings;
			if (text.isBlank())
				settings = MAPPER.createObjectNode();
			else
			{
				settings = parse(text, file.toString());
				if (settings == null)
					return;
			}
			into.add(new Layer(scope, file.toString(), settings, root, certain));
		}

		/**
		 * Parses settings.
		 *
		 * @param text   the JSON
		 * @param source the source as the user knows it
		 * @return the settings, or {@code null} after listing a problem
		 */
		private JsonNode parse(String text, String source)
		{
			try
			{
				JsonNode settings = MAPPER.readTree(text);
				if (settings != null && settings.isObject())
					return settings;
				problems.add("CAT cannot read the settings in " + source + ": they are not a JSON object");
			}
			catch (JacksonException e)
			{
				problems.add("CAT cannot read the settings in " + source + ": " + e.getOriginalMessage());
			}
			return null;
		}

		/**
		 * Adds every grant that the settings and the command line give.
		 */
		private void addGrants()
		{
			List<Path> projects = session.projectDirectories();
			for (int i = 0; i < projects.size(); ++i)
			{
				grant(projects.get(i), "the session's project folder", !(session.projectKnown() && i == 0));
			}
			if (session.launch().isPresent())
			{
				for (String directory : session.launch().get().addDirectories())
				{
					for (int i = 0; i < projects.size(); ++i)
					{
						try
						{
							grant(projects.get(i).resolve(directory), "Claude Code's option --add-dir",
								!(session.projectKnown() && i == 0));
						}
						catch (InvalidPathException _)
						{
							problems.add("CAT cannot read the --add-dir value " + directory + ": it is not a path");
						}
					}
				}
			}
			for (Path root : BUILT_IN_WRITES)
				grant(root, "Claude Code's built-in write folders", false);
			session.temporaryDirectory().ifPresent(directory -> grant(directory, "the session's temporary folder",
				false));
			boolean filesystemDisabledTrue = false;
			for (Layer layer : layers)
			{
				boolean ignoredProject = layer.scope() == Scope.PROJECT && projectRestricted;
				boolean notManaged = managedRulesOnly && layer.scope() != Scope.MANAGED;
				for (String entry : strings(layer, "sandbox", "filesystem", "allowWrite"))
				{
					addEntry(entry, Syntax.SANDBOX, layer, "sandbox.filesystem.allowWrite in " + layer.source(),
						layer.certain() && !ignoredProject && isList(layer, "sandbox", "filesystem", "allowWrite"));
				}
				for (String entry : strings(layer, "permissions", "additionalDirectories"))
				{
					addEntry(entry, Syntax.SANDBOX, layer, "permissions.additionalDirectories in " + layer.source(),
						layer.certain() && !notManaged && isList(layer, "permissions", "additionalDirectories"));
				}
				for (String rule : strings(layer, "permissions", "allow"))
				{
					Optional<String> specifier = specifier(rule, "Edit");
					if (specifier.isPresent())
					{
						addEntry(specifier.get(), Syntax.RULE, layer, "permissions.allow in " + layer.source(),
							layer.certain() && !notManaged && !ignoredProject && isList(layer, "permissions", "allow"));
					}
				}
				Optional<Boolean> lifted = bool(layer, "sandbox", "filesystem", "disabled");
				if (lifted.isPresent() && lifted.get())
				{
					filesystemDisabledTrue = true;
					for (Path root : FileSystems.getDefault().getRootDirectories())
						grant(root, "sandbox.filesystem.disabled in " + layer.source(), !layer.certain());
				}
			}
			if (filesystemDisabledTrue)
			{
				// A layer that sets the opposite makes the lifting uncertain
				boolean opposite = false;
				for (Layer layer : layers)
				{
					Optional<Boolean> lifted = bool(layer, "sandbox", "filesystem", "disabled");
					if (lifted.isPresent() && !lifted.get())
						opposite = true;
				}
				if (opposite)
				{
					for (int i = 0; i < grants.size(); ++i)
					{
						WriteGrant grant = grants.get(i);
						if (grant.setting().startsWith("sandbox.filesystem.disabled"))
							grants.set(i, new WriteGrant(grant.path(), grant.setting(), true));
					}
				}
			}
		}

		/**
		 * Adds the grants that an entry of the settings may give.
		 *
		 * @param entry   the entry
		 * @param syntax  the syntax of the entry
		 * @param layer   the layer that holds the entry
		 * @param setting the setting that holds the entry, as the user knows it
		 * @param certain {@code true} if CAT is certain that the setting applies
		 */
		private void addEntry(String entry, Syntax syntax, Layer layer, String setting, boolean certain)
		{
			for (Named named : name(entry, syntax, layer.root()))
				grant(named.path(), setting, !certain || !named.certain());
		}

		/**
		 * Adds a grant.
		 *
		 * @param path     the folder
		 * @param setting  the setting that gives it, as the user knows it
		 * @param possible {@code true} if CAT cannot tell that the sandbox applies it
		 */
		private void grant(Path path, String setting, boolean possible)
		{
			if (!path.isAbsolute())
				return;
			grants.add(new WriteGrant(path.normalize(), setting, possible || uncertainEverything));
		}

		/**
		 * Adds the denials that CAT is certain of.
		 */
		private void addDenials()
		{
			for (Layer layer : layers)
			{
				if (!layer.certain())
					continue;
				for (String entry : strings(layer, "sandbox", "filesystem", "denyWrite"))
					addDenial(entry, Syntax.SANDBOX, layer.root());
				for (String rule : strings(layer, "permissions", "deny"))
				{
					Optional<String> specifier = specifier(rule, "Edit");
					if (specifier.isPresent())
						addDenial(specifier.get(), Syntax.RULE, layer.root());
				}
			}
			if (session.launch().isPresent() && session.projectKnown() && !session.projectDirectories().isEmpty())
			{
				for (String rule : session.launch().get().deniedTools())
				{
					Optional<String> specifier = specifier(rule, "Edit");
					if (specifier.isPresent())
						addDenial(specifier.get(), Syntax.RULE, session.projectDirectories().getFirst());
				}
			}
			denials.add(session.configurationDirectory());
			denials.addAll(session.managedDirectories());
			for (WriteGrant grant : grants)
			{
				if (grant.possible())
					continue;
				for (String inside : BUILT_IN_WRITE_DENIALS)
					denials.add(grant.path().resolve(inside));
			}
		}

		/**
		 * Adds a denial if the entry names exactly one folder.
		 *
		 * @param entry  the entry
		 * @param syntax the syntax of the entry
		 * @param root   the folder that a relative path is relative to
		 */
		private void addDenial(String entry, Syntax syntax, Path root)
		{
			if (entry.isBlank() || entry.equals("**") || entry.equals("//**"))
				return;
			List<Named> named = name(entry, syntax, root);
			if (named.size() == 1 && named.getFirst().certain() && !isPattern(entry))
				denials.add(named.getFirst().path());
		}

		/**
		 * Returns the folders that an entry may name.
		 *
		 * @param entry  the entry
		 * @param syntax the syntax of the entry
		 * @param root   the folder that a relative path is relative to
		 * @return the folders
		 */
		private List<Named> name(String entry, Syntax syntax, Path root)
		{
			if (entry.isBlank())
				return List.of();
			Candidates candidates = candidates(entry, syntax, root);
			if (candidates.paths().isEmpty())
			{
				// Another user's home folder could be any folder beneath the folder that holds the home folders
				Path parent = session.userHome().getParent();
				if (parent == null)
					return List.of();
				return List.of(new Named(parent, false));
			}
			List<Named> named = new ArrayList<>();
			for (String candidate : candidates.paths())
			{
				String path = candidate;
				if (path.endsWith("/**"))
					path = path.substring(0, path.length() - "/**".length());
				if (path.isEmpty())
					path = "/";
				boolean pattern = isPattern(path);
				if (pattern)
					path = literalAncestor(path);
				try
				{
					Path folder = Path.of(path).normalize();
					boolean certain = candidates.exact() && candidates.paths().size() == 1 && !pattern;
					named.add(new Named(folder, certain));
				}
				catch (InvalidPathException _)
				{
					problems.add("CAT cannot read the path " + candidate + ": it is not a path");
				}
			}
			return named;
		}

		/**
		 * Returns the ways that the release may read a spelled path.
		 *
		 * @param entry  the entry
		 * @param syntax the syntax of the entry
		 * @param root   the folder that a relative path is relative to
		 * @return the candidates, which are empty if the path names another user's home folder
		 */
		private Candidates candidates(String entry, Syntax syntax, Path root)
		{
			String spelled = entry;
			if (windows)
			{
				if (WINDOWS_ABSOLUTE.matcher(entry).find())
					return new Candidates(List.of(entry.replace('\\', '/')), true);
				spelled = entry.replace('\\', '/');
			}
			if (spelled.equals("~") || spelled.startsWith("~/"))
				return new Candidates(List.of(join(session.userHome(), spelled.substring(1))), true);
			if (spelled.startsWith("~"))
				return new Candidates(List.of(), false);
			return switch (syntax)
			{
				case SANDBOX -> sandboxCandidates(spelled, root);
				case RULE -> ruleCandidates(spelled, root);
			};
		}

		/**
		 * Returns the ways that the release may read a path of the sandbox's file settings.
		 *
		 * @param entry the entry
		 * @param root  the settings' root
		 * @return the candidates
		 */
		private Candidates sandboxCandidates(String entry, Path root)
		{
			// "//" may start a network path, which the release does not treat as a local folder
			if (entry.startsWith("//"))
				return new Candidates(List.of(entry.substring(1)), false);
			if (entry.startsWith("/"))
				return new Candidates(List.of(entry), true);
			Set<String> paths = new LinkedHashSet<>();
			paths.add(join(root, "/" + entry));
			for (Path project : session.projectDirectories())
				paths.add(join(project, "/" + entry));
			// The release may resolve the path against the project, which CAT may not know
			boolean exact = paths.size() == 1 && session.projectKnown();
			return new Candidates(List.copyOf(paths), exact);
		}

		/**
		 * Returns the ways that the release may read the specifier of a permission rule.
		 *
		 * @param entry the specifier
		 * @param root  the settings' root
		 * @return the candidates
		 */
		private Candidates ruleCandidates(String entry, Path root)
		{
			if (entry.startsWith("//"))
				return new Candidates(List.of(ruleDrive(entry.substring(1))), true);
			if (entry.startsWith("/"))
			{
				String fromRoot = join(root, entry);
				if (fromRoot.equals(entry))
					return new Candidates(List.of(entry), true);
				// Whether such a path is relative to the settings' root or absolute is not certain
				return new Candidates(List.of(fromRoot, entry), false);
			}
			String relative = entry;
			boolean bare = false;
			if (relative.startsWith("./"))
				relative = relative.substring(2);
			else if (!relative.contains("/"))
				bare = true;
			Set<String> paths = new LinkedHashSet<>();
			List<Path> projects = session.projectDirectories();
			if (projects.isEmpty())
				projects = List.of(root);
			for (Path project : projects)
			{
				paths.add(join(project, "/" + relative));
				if (bare)
					paths.add(join(project, "/**/" + relative));
			}
			return new Candidates(List.copyOf(paths), paths.size() == 1 && session.projectKnown());
		}

		/**
		 * Returns a path of a permission rule that starts with {@code //} in the form of the platform.
		 *
		 * @param path the path
		 * @return the path
		 */
		private String ruleDrive(String path)
		{
			if (!windows)
				return path;
			Matcher drive = RULE_DRIVE.matcher(path);
			if (!drive.matches())
				return path;
			String rest = drive.group(2);
			if (rest == null)
				rest = "/";
			return Character.toUpperCase(drive.group(1).charAt(0)) + ":" + rest;
		}

		/**
		 * Joins a folder and a path that starts with a slash.
		 *
		 * @param folder the folder
		 * @param path   the path
		 * @return the joined path, spelled with forward slashes
		 */
		private String join(Path folder, String path)
		{
			String prefix = folder.toString();
			if (windows)
				prefix = prefix.replace('\\', '/');
			if (prefix.endsWith("/"))
				prefix = prefix.substring(0, prefix.length() - 1);
			String joined = prefix + path;
			if (joined.isEmpty())
				return "/";
			return joined;
		}

		/**
		 * Returns the value of a Boolean setting.
		 *
		 * @param layer the layer
		 * @param keys  the keys that lead to the setting
		 * @return the value, or empty if the layer does not set it or sets it to another type
		 */
		private Optional<Boolean> bool(Layer layer, String... keys)
		{
			JsonNode node = at(layer, keys);
			if (node.isMissingNode())
				return Optional.empty();
			if (node.isBoolean())
				return Optional.of(node.booleanValue());
			wrongType(layer, keys, "true or false");
			return Optional.empty();
		}

		/**
		 * Indicates whether a layer of a scope sets a Boolean setting to {@code true}.
		 *
		 * @param scope the scope
		 * @param keys  the keys that lead to the setting
		 * @return {@code true} if one does
		 */
		private boolean anyTrue(Scope scope, String... keys)
		{
			for (Layer layer : layers)
			{
				if (layer.scope() == scope && bool(layer, keys).orElse(false))
					return true;
			}
			return false;
		}

		/**
		 * Indicates whether a layer of a scope sets a Boolean setting to {@code false}.
		 *
		 * @param scope the scope
		 * @param keys  the keys that lead to the setting
		 * @return {@code true} if one does
		 */
		private boolean anyFalse(Scope scope, String... keys)
		{
			for (Layer layer : layers)
			{
				if (layer.scope() == scope && !bool(layer, keys).orElse(true))
					return true;
			}
			return false;
		}

		/**
		 * Returns a setting's value.
		 *
		 * @param layer the layer
		 * @param keys  the keys that lead to the setting
		 * @return the value, or a missing node if the layer does not set it or a level on the way is not an object
		 */
		private JsonNode at(Layer layer, String... keys)
		{
			JsonNode node = layer.settings();
			for (int i = 0; i < keys.length; ++i)
			{
				node = node.path(keys[i]);
				if (node.isMissingNode() || node.isNull())
					return MAPPER.missingNode();
				if (i < keys.length - 1 && !node.isObject())
				{
					wrongType(layer, Arrays.copyOf(keys, i + 1), "an object");
					return MAPPER.missingNode();
				}
			}
			return node;
		}

		/**
		 * Returns the strings that a setting lists. A single string counts as a list of one.
		 *
		 * @param layer the layer
		 * @param keys  the keys that lead to the setting
		 * @return the strings
		 */
		private List<String> strings(Layer layer, String... keys)
		{
			JsonNode node = at(layer, keys);
			if (node.isMissingNode())
				return List.of();
			if (node.isString())
				return List.of(node.stringValue());
			if (!node.isArray())
			{
				wrongType(layer, keys, "a list");
				return List.of();
			}
			List<String> strings = new ArrayList<>();
			for (JsonNode element : node.values())
			{
				if (element.isString())
					strings.add(element.stringValue());
				else
					wrongType(layer, keys, "a list of strings");
			}
			return strings;
		}

		/**
		 * Indicates whether a setting that lists strings is given as a list, which is the form that the release
		 * documents, rather than as a single string.
		 *
		 * @param layer the layer
		 * @param keys  the keys that lead to the setting
		 * @return {@code false} if the layer gives a single string
		 */
		private boolean isList(Layer layer, String... keys)
		{
			return !at(layer, keys).isString();
		}

		/**
		 * Lists a setting that has the wrong type as a problem.
		 *
		 * @param layer    the layer
		 * @param keys     the keys that lead to the setting
		 * @param expected what the setting should be
		 */
		private void wrongType(Layer layer, String[] keys, String expected)
		{
			problems.add("CAT cannot read " + String.join(".", keys) + " in " + layer.source() + ": it is not " +
				expected);
		}
	}

	/**
	 * Returns the specifier of a permission rule for a tool.
	 *
	 * @param rule the rule
	 * @param tool the tool's name
	 * @return the specifier, or empty if the rule is for another tool or has none
	 */
	private static Optional<String> specifier(String rule, String tool)
	{
		Matcher matcher = RULE.matcher(rule.strip());
		if (!matcher.matches() || !matcher.group(1).equals(tool) || matcher.group(2) == null)
			return Optional.empty();
		return Optional.of(matcher.group(2));
	}

	/**
	 * Indicates whether a path holds a pattern.
	 *
	 * @param path the path
	 * @return {@code true} if it does
	 */
	private static boolean isPattern(String path)
	{
		for (int i = 0; i < path.length(); ++i)
		{
			if (GLOB_CHARACTERS.indexOf(path.charAt(i)) >= 0)
				return true;
		}
		return false;
	}

	/**
	 * Returns the longest folder that a pattern spells out before its first wildcard.
	 *
	 * @param pattern the pattern
	 * @return the folder
	 */
	private static String literalAncestor(String pattern)
	{
		int firstGlob = pattern.length();
		for (int i = 0; i < pattern.length(); ++i)
		{
			if (GLOB_CHARACTERS.indexOf(pattern.charAt(i)) >= 0)
			{
				firstGlob = i;
				break;
			}
		}
		String literal = pattern.substring(0, pattern.lastIndexOf('/', firstGlob) + 1);
		if (literal.isEmpty())
			literal = "/";
		return literal;
	}

	/**
	 * Removes the duplicate grants, keeping the order.
	 *
	 * @param grants the grants
	 * @return the distinct grants
	 */
	private static List<WriteGrant> distinct(List<WriteGrant> grants)
	{
		return List.copyOf(new LinkedHashSet<>(grants));
	}
}
