package com.holdmyspot.cat.codex.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import com.holdmyspot.cat.common.SandboxWrites;
import com.holdmyspot.cat.common.WriteGrant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Reads where Codex's sandbox may let commands write, to warn of a folder on {@code PATH} that a command could plant a
 * program in.
 * <p>
 * The reading overestimates. It scans every layer that Codex could apply, without working out which one wins, and
 * reports a grant as possible whenever it cannot be sure that the sandbox applies it: the session's command line is
 * unknown, the sandbox mode is not certain, the grant comes from a project configuration that Codex may not trust, a
 * path cannot be resolved for certain, or a recorded policy might have hidden it. It applies a denial only when it is
 * sure of the grant that the denial limits, and lists in the problems what it cannot read.
 * <p>
 * A session that has no sandbox has no grant to report: no layer and no option names a sandbox mode, or the only mode
 * that they name is {@code danger-full-access}. The missing sandbox already lets commands write anywhere.
 */
public final class CodexWriteGrants
{
	/**
	 * Bounds a configuration file to 1 MiB.
	 */
	private static final int MAX_BYTES = 1024 * 1024;
	/**
	 * The names that Codex protects inside a write root.
	 */
	private static final List<String> PROTECTED_NAMES = List.of(".git", ".codex", ".agents", ".aws");
	/**
	 * The name of a profile whose file Codex reads.
	 */
	private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9_-]+");
	/**
	 * The sandbox mode that runs commands without a sandbox.
	 */
	private static final String NO_SANDBOX_MODE = "danger-full-access";
	/**
	 * The sandbox modes that give commands no write grant.
	 */
	private static final Set<String> NO_WRITE_MODES = Set.of("read-only", NO_SANDBOX_MODE);
	/**
	 * The sandbox mode that grants the working directory and the temporary folders.
	 */
	private static final String WORKSPACE_WRITE = "workspace-write";
	/**
	 * The key of the table that configures the workspace-write mode.
	 */
	private static final String WORKSPACE_TABLE = "sandbox_workspace_write";

	/**
	 * Prevents instantiation.
	 */
	private CodexWriteGrants()
	{
	}

	/**
	 * The command line options of a Codex session.
	 *
	 * @param overrides        the {@code -c} overrides, each a {@code key=value} text
	 * @param profile          the profile that {@code --profile} names
	 * @param sandboxMode      the sandbox mode that {@code --sandbox} or {@code --full-auto} gives
	 * @param bypassSandbox    {@code true} if the session runs without a sandbox
	 * @param addDirs          the folders that {@code --add-dir} adds
	 * @param ignoreUserConfig {@code true} if the session ignores the user's {@code config.toml}
	 */
	public record Launch(List<String> overrides, Optional<String> profile, Optional<String> sandboxMode,
		boolean bypassSandbox, List<String> addDirs, boolean ignoreUserConfig)
	{
		/**
		 * Creates the launch.
		 *
		 * @param overrides        the {@code -c} overrides, each a {@code key=value} text
		 * @param profile          the profile that {@code --profile} names
		 * @param sandboxMode      the sandbox mode that {@code --sandbox} or {@code --full-auto} gives
		 * @param bypassSandbox    {@code true} if the session runs without a sandbox
		 * @param addDirs          the folders that {@code --add-dir} adds
		 * @param ignoreUserConfig {@code true} if the session ignores the user's {@code config.toml}
		 * @throws NullPointerException if any argument is null
		 */
		public Launch
		{
			overrides = List.copyOf(overrides);
			requireThat(profile, "profile").isNotNull();
			requireThat(sandboxMode, "sandboxMode").isNotNull();
			addDirs = List.copyOf(addDirs);
		}
	}

	/**
	 * A policy that a rollout records for a turn of the session.
	 *
	 * @param sandboxed  {@code true} if the policy sandboxes the session's commands
	 * @param writeRoots the folders that the policy lets the commands write
	 */
	public record Recorded(boolean sandboxed, List<Path> writeRoots)
	{
		/**
		 * Creates the recorded.
		 *
		 * @param sandboxed  {@code true} if the policy sandboxes the session's commands
		 * @param writeRoots the folders that the policy lets the commands write
		 * @throws NullPointerException     if {@code writeRoots} is null
		 * @throws IllegalArgumentException if a root is relative
		 */
		public Recorded
		{
			writeRoots = List.copyOf(writeRoots);
			for (Path root : writeRoots)
				requireThat(root, "root").isAbsolute();
		}
	}

	/**
	 * What CAT knows of a session.
	 *
	 * @param system             the machine-wide folder, which holds the machine-wide configuration
	 * @param managedPreferences the files in which a platform delivers managed configuration, which CAT cannot parse
	 * @param codexHome          Codex's home
	 * @param userHome           the user's home folder
	 * @param workingDirectory   the session's working directory, or the folder that CAT reads project configuration
	 *                           from
	 * @param environment        the session's environment variables
	 * @param slashTmp           the folder that Codex's {@code /tmp} grant names, if any
	 * @param launch             the session's command line, or empty if CAT cannot read it
	 * @param recorded           the policies that the session's rollouts record
	 */
	public record Session(CodexSystemFolder system, List<Path> managedPreferences, Path codexHome, Path userHome,
		Path workingDirectory, Map<String, String> environment, Optional<Path> slashTmp, Optional<Launch> launch,
		List<Recorded> recorded)
	{
		/**
		 * Creates the session.
		 *
		 * @param system             the machine-wide folder, which holds the machine-wide configuration
		 * @param managedPreferences the files in which a platform delivers managed configuration, which CAT cannot parse
		 * @param codexHome          Codex's home
		 * @param userHome           the user's home folder
		 * @param workingDirectory   the session's working directory, or the folder that CAT reads project configuration
	 *                           from
		 * @param environment        the session's environment variables
		 * @param slashTmp           the folder that Codex's {@code /tmp} grant names, if any
		 * @param launch             the session's command line, or empty if CAT cannot read it
		 * @param recorded           the policies that the session's rollouts record
		 * @throws NullPointerException     if any argument is null
		 * @throws IllegalArgumentException if a path is relative
		 */
		public Session
		{
			requireThat(system, "system").isNotNull();
			managedPreferences = List.copyOf(managedPreferences);
			requireThat(codexHome, "codexHome").isAbsolute();
			requireThat(userHome, "userHome").isAbsolute();
			requireThat(workingDirectory, "workingDirectory").isAbsolute();
			environment = Map.copyOf(environment);
			requireThat(slashTmp, "slashTmp").isNotNull();
			requireThat(launch, "launch").isNotNull();
			recorded = List.copyOf(recorded);
		}
	}

	/**
	 * Returns the files in which a platform delivers Codex's managed configuration. Windows delivers none in files, so
	 * there the list is empty.
	 *
	 * @param user the name of the user that runs Codex
	 * @return the files
	 * @throws NullPointerException if {@code user} is null
	 */
	public static List<Path> managedPreferences(String user)
	{
		requireThat(user, "user").isNotNull();
		Path folder = Path.of("/Library/Managed Preferences");
		if (!folder.isAbsolute())
			return List.of();
		if (user.isBlank() || user.contains("/"))
			return List.of(folder.resolve("com.openai.codex.plist"));
		return List.of(folder.resolve("com.openai.codex.plist"), folder.resolve(user).resolve("com.openai.codex.plist"));
	}

	/**
	 * Reads where the session's sandbox may let commands write.
	 *
	 * @param session what CAT knows of the session
	 * @return the grants, the denials that CAT is sure of, and the problems
	 * @throws NullPointerException if {@code session} is null
	 */
	public static SandboxWrites read(Session session)
	{
		requireThat(session, "session").isNotNull();
		return new Reading(session).read();
	}

	/**
	 * A configuration that Codex may apply.
	 *
	 * @param source   how the setting names it: the file or the override
	 * @param document the content
	 * @param applied  {@code true} if Codex applies it for certain, given the session's command line
	 * @param folder   the folder that a relative path in it may be relative to, or null if none
	 */
	private record Layer(String source, JsonNode document, boolean applied, Path folder)
	{
	}

	/**
	 * Reads one session.
	 */
	private static final class Reading
	{
		private final Session session;
		private final boolean launchKnown;
		private final Set<String> problems = new LinkedHashSet<>();
		private final List<Layer> layers = new ArrayList<>();
		private final Map<Path, WriteGrant> grants = new LinkedHashMap<>();
		private final Set<Path> certainWorkspaceRoots = new LinkedHashSet<>();
		private boolean certainMode;

		/**
		 * Creates a reading.
		 *
		 * @param session the session
		 */
		private Reading(Session session)
		{
			this.session = session;
			this.launchKnown = session.launch().isPresent();
		}

		/**
		 * Reads the session.
		 *
		 * @return the writes
		 */
		private SandboxWrites read()
		{
			session.system().fallbackCause().ifPresent(problems::add);
			for (Path preferences : session.managedPreferences())
			{
				if (Files.exists(preferences))
				{
					problems.add("Codex reads managed preferences from " + preferences + ", which CAT cannot parse, so " +
						"it cannot tell what they grant");
				}
			}
			Optional<Launch> launch = session.launch();
			if (launch.isPresent() && launch.get().bypassSandbox())
				return result(session.recorded());
			addLayers();
			Set<String> modes = new TreeSet<>();
			boolean permissionsInUse = false;
			for (Layer layer : layers)
			{
				collectModes(layer, modes);
				if (layer.document().has("default_permissions") || layer.document().path("permissions").isObject())
					permissionsInUse = true;
			}
			launch.flatMap(Launch::sandboxMode).ifPresent(modes::add);
			if (!permissionsInUse && (hasNoSandbox(modes) || launchKnown && NO_WRITE_MODES.containsAll(modes)))
				return result(session.recorded());
			certainMode = launchKnown && !permissionsInUse && modes.equals(Set.of(WORKSPACE_WRITE));
			addImplicitGrants();
			for (Layer layer : layers)
			{
				addWritableRoots(layer, layer.document().path(WORKSPACE_TABLE), layer.applied(), "");
				for (Entry<String, JsonNode> profile : layer.document().path("profiles").properties())
				{
					addWritableRoots(layer, profile.getValue().path(WORKSPACE_TABLE), false,
						"profiles." + profile.getKey() + ".");
				}
				addPermissionProfiles(layer);
			}
			launch.ifPresent(this::addAddedFolders);
			return result(session.recorded());
		}

		/**
		 * Indicates whether no layer and no option gives the session a sandbox: none names a mode, or the only mode
		 * that they name runs commands without one. A session without a sandbox has no grant to report, because the
		 * missing sandbox already lets commands write anywhere, and that includes the working directory and the
		 * temporary folders. Modes that disagree leave the session possibly sandboxed, so they do not count.
		 *
		 * @param modes the sandbox modes that the layers and the options name
		 * @return {@code true} if the session has no sandbox
		 */
		private static boolean hasNoSandbox(Set<String> modes)
		{
			return modes.isEmpty() || modes.equals(Set.of(NO_SANDBOX_MODE));
		}

		/**
		 * Builds the result.
		 *
		 * @param recorded the recorded policies
		 * @return the writes
		 */
		private SandboxWrites result(List<Recorded> recorded)
		{
			List<WriteGrant> found = new ArrayList<>();
			List<Path> roots = new ArrayList<>();
			for (Recorded policy : recorded)
			{
				if (policy.sandboxed())
					roots.addAll(policy.writeRoots());
			}
			if (!recorded.isEmpty())
			{
				for (WriteGrant grant : grants.values())
				{
					if (!grant.possible() && !coveredBy(grant.path(), roots))
						found.add(new WriteGrant(grant.path(), grant.setting(), true));
					else
						found.add(grant);
				}
				for (Path root : roots)
				{
					found.add(new WriteGrant(root, "the policy that a rollout of this session records", false));
					certainWorkspaceRoots.add(root);
				}
			}
			else
				found.addAll(grants.values());
			List<Path> denials = new ArrayList<>();
			for (Path root : certainWorkspaceRoots)
			{
				for (String name : PROTECTED_NAMES)
					denials.add(root.resolve(name));
			}
			return new SandboxWrites(found, denials, new ArrayList<>(problems));
		}

		/**
		 * Indicates whether a folder lies inside one of the roots.
		 *
		 * @param path  the folder
		 * @param roots the roots
		 * @return {@code true} if it does
		 */
		private static boolean coveredBy(Path path, List<Path> roots)
		{
			for (Path root : roots)
			{
				if (path.startsWith(root))
					return true;
			}
			return false;
		}

		/**
		 * Adds every configuration that Codex may apply to the session.
		 */
		private void addLayers()
		{
			Optional<Launch> launch = session.launch();
			Path system = session.system().folder();
			addFile(system.resolve("config.toml"), launchKnown, system);
			boolean ignoreUser = launch.isPresent() && launch.get().ignoreUserConfig();
			if (!ignoreUser)
				addFile(session.codexHome().resolve("config.toml"), launchKnown, session.codexHome());
			if (launch.isPresent())
			{
				if (launch.get().profile().isPresent())
				{
					String profile = launch.get().profile().get();
					if (PROFILE_NAME.matcher(profile).matches())
						addFile(session.codexHome().resolve(profile + ".config.toml"), true, session.codexHome());
					else
						problems.add("Codex runs with the profile \"" + profile + "\", whose file CAT cannot find");
				}
			}
			else
				addEveryProfileFile();
			Path userConfiguration = session.codexHome().resolve("config.toml").normalize();
			for (Path folder = session.workingDirectory(); folder != null; folder = folder.getParent())
			{
				Path file = folder.resolve(".codex").resolve("config.toml");
				if (!file.normalize().equals(userConfiguration))
					addFile(file, false, folder);
			}
			launch.ifPresent(options -> options.overrides().forEach(this::addOverride));
			Optional<Path> managed = session.system().managedConfig();
			if (managed.isPresent())
				addFile(managed.get(), launchKnown, system);
		}

		/**
		 * Adds the configuration file of every profile, because the session's command line does not say which one it
		 * runs with.
		 */
		private void addEveryProfileFile()
		{
			if (!Files.isDirectory(session.codexHome()))
				return;
			try (DirectoryStream<Path> files = Files.newDirectoryStream(session.codexHome(), "*.config.toml"))
			{
				for (Path file : files)
					addFile(file, false, session.codexHome());
			}
			catch (IOException e)
			{
				problems.add("CAT could not list the profile files in " + session.codexHome() + ": " + e.getMessage());
			}
		}

		/**
		 * Adds the layer that a file holds, if the file exists.
		 *
		 * @param file    the file
		 * @param applied {@code true} if Codex applies the file for certain
		 * @param folder  the folder that a relative path in the file may be relative to
		 */
		private void addFile(Path file, boolean applied, Path folder)
		{
			if (!Files.isRegularFile(file))
				return;
			try
			{
				if (Files.size(file) > MAX_BYTES)
				{
					problems.add("CAT could not read " + file + ", because it exceeds 1 MiB, so it cannot tell " +
						"what the file grants");
					return;
				}
				JsonNode document = TomlText.tryParse(Files.readString(file, StandardCharsets.UTF_8));
				if (document == null || !document.isObject())
				{
					problems.add("CAT could not read " + file + ", because it is not valid TOML, so it cannot tell " +
						"what the file grants");
					return;
				}
				layers.add(new Layer(file.toString(), document, applied, folder));
			}
			catch (IOException e)
			{
				problems.add("CAT could not read " + file + ", so it cannot tell what the file grants: " +
					e.getMessage());
			}
		}

		/**
		 * Adds the layer that a {@code -c} override holds. Codex reads the value as TOML and falls back to a string.
		 *
		 * @param override the override, as {@code key=value}
		 */
		private void addOverride(String override)
		{
			int equals = override.indexOf('=');
			if (equals > 0)
			{
				String key = override.substring(0, equals).strip();
				String value = override.substring(equals + 1).strip();
				JsonNode document = TomlText.tryParse(key + " = " + value);
				if (document == null)
					document = TomlText.tryParse(key + " = " + TomlText.quote(value));
				if (document != null && document.isObject())
				{
					layers.add(new Layer("the -c override " + key, document, true, null));
					return;
				}
			}
			problems.add("CAT could not parse the -c override \"" + override + "\", so it cannot tell what it grants");
		}

		/**
		 * Collects the sandbox modes that a layer names, at its top level and in its profiles.
		 *
		 * @param layer the layer
		 * @param modes the modes found so far
		 */
		private void collectModes(Layer layer, Set<String> modes)
		{
			collectMode(layer, layer.document(), "sandbox_mode", modes);
			for (Entry<String, JsonNode> profile : layer.document().path("profiles").properties())
				collectMode(layer, profile.getValue(), "profiles." + profile.getKey() + ".sandbox_mode", modes);
		}

		/**
		 * Collects the sandbox mode of one table.
		 *
		 * @param layer the layer that holds the table
		 * @param table the table
		 * @param name  the dotted name of the setting
		 * @param modes the modes found so far
		 */
		private void collectMode(Layer layer, JsonNode table, String name, Set<String> modes)
		{
			JsonNode mode = table.path("sandbox_mode");
			if (mode.isMissingNode())
				return;
			if (mode.isString())
				modes.add(mode.stringValue());
			else
			{
				modes.add("unknown");
				problems.add(name + " in " + layer.source() + " is not a string, so CAT cannot tell which sandbox mode " +
					"it selects");
			}
		}

		/**
		 * Adds the grants that the workspace-write mode makes without a configured path: the working directory, and
		 * {@code /tmp} and the temporary folder unless a configuration excludes them.
		 */
		private void addImplicitGrants()
		{
			addWorkspace(session.workingDirectory(), "the workspace-write mode's grant of the working directory", true);
			boolean excludesSlashTmp = anyLayerMentions("exclude_slash_tmp");
			boolean excludesTmpdir = anyLayerMentions("exclude_tmpdir_env_var");
			session.slashTmp().ifPresent(folder -> addWorkspace(folder, "the workspace-write mode's grant of /tmp",
				!excludesSlashTmp));
			String tmpdir = session.environment().getOrDefault("TMPDIR", "");
			if (!tmpdir.isBlank())
			{
				try
				{
					Path folder = Path.of(tmpdir);
					if (folder.isAbsolute())
					{
						addWorkspace(folder.normalize(), "the workspace-write mode's grant of the TMPDIR folder",
							!excludesTmpdir);
					}
				}
				catch (InvalidPathException _)
				{
					// Codex ignores a TMPDIR that is not a path
				}
			}
		}

		/**
		 * Indicates whether any layer sets a key of the workspace-write table.
		 *
		 * @param key the key
		 * @return {@code true} if one does
		 */
		private boolean anyLayerMentions(String key)
		{
			for (Layer layer : layers)
			{
				if (layer.document().path(WORKSPACE_TABLE).has(key))
					return true;
				for (Entry<String, JsonNode> profile : layer.document().path("profiles").properties())
				{
					if (profile.getValue().path(WORKSPACE_TABLE).has(key))
						return true;
				}
			}
			return false;
		}

		/**
		 * Adds a grant of the workspace-write mode.
		 *
		 * @param folder  the folder
		 * @param setting what grants it
		 * @param certain {@code true} if the grant is certain whenever the mode is
		 */
		private void addWorkspace(Path folder, String setting, boolean certain)
		{
			boolean sure = certain && certainMode;
			add(folder, setting, sure);
			if (sure)
				certainWorkspaceRoots.add(folder);
		}

		/**
		 * Adds the roots that a table of the workspace-write mode lists.
		 *
		 * @param layer   the layer that holds the table
		 * @param table   the table
		 * @param certain {@code true} if the grant is certain whenever the mode is
		 * @param prefix  the dotted name of the table's parent, ending in a dot, or empty
		 */
		private void addWritableRoots(Layer layer, JsonNode table, boolean certain, String prefix)
		{
			JsonNode roots = table.path("writable_roots");
			if (roots.isMissingNode())
				return;
			String name = prefix + WORKSPACE_TABLE + ".writable_roots";
			String setting = name + " in " + layer.source();
			if (!roots.isArray())
			{
				problems.add(name + " in " + layer.source() + " is not a list of paths, so CAT cannot tell what it " +
					"grants");
				return;
			}
			for (JsonNode root : roots)
			{
				if (!root.isString())
				{
					problems.add(name + " in " + layer.source() + " holds a value that is not a path, so CAT cannot " +
						"tell what it grants");
					continue;
				}
				for (Entry<Path, Boolean> candidate : candidates(root.stringValue(), layer.folder(), name,
					layer.source()).entrySet())
				{
					boolean sure = certain && candidate.getValue();
					add(candidate.getKey(), setting, sure && certainMode);
					if (sure && certainMode)
						certainWorkspaceRoots.add(candidate.getKey());
				}
			}
		}

		/**
		 * Adds the folders that {@code --add-dir} names.
		 *
		 * @param launch the command line options
		 */
		private void addAddedFolders(Launch launch)
		{
			for (String text : launch.addDirs())
			{
				for (Entry<Path, Boolean> candidate : candidates(text, null, "--add-dir", "the command line").entrySet())
				{
					boolean sure = candidate.getValue() && certainMode;
					add(candidate.getKey(), "--add-dir", sure);
					if (sure)
						certainWorkspaceRoots.add(candidate.getKey());
				}
			}
		}

		/**
		 * Adds the grants of the permission profiles in a layer, all of them possible, because CAT does not work out
		 * which profile the session selects.
		 *
		 * @param layer the layer
		 */
		private void addPermissionProfiles(Layer layer)
		{
			for (Entry<String, JsonNode> profile : layer.document().path("permissions").properties())
			{
				String name = "permissions." + profile.getKey() + ".filesystem";
				String setting = name + " in " + layer.source();
				boolean usesWorkspaceRoots = false;
				for (Entry<String, JsonNode> entry : profile.getValue().path("filesystem").properties())
				{
					List<Path> bases = bases(entry.getKey(), layer, name);
					if (entry.getKey().equals(":workspace_roots") || entry.getKey().equals(":project_roots"))
						usesWorkspaceRoots = usesWorkspaceRoots || grantsWrite(entry.getValue());
					JsonNode access = entry.getValue();
					if (access.isObject())
					{
						for (Entry<String, JsonNode> sub : access.properties())
						{
							if (!isNoWrite(sub.getValue()))
							{
								for (Path base : bases)
									addSubpath(base, sub.getKey(), setting);
							}
						}
					}
					else if (!isNoWrite(access))
					{
						for (Path base : bases)
							add(base, setting, false);
					}
				}
				if (usesWorkspaceRoots)
				{
					for (JsonNode root : profile.getValue().path("workspace_roots"))
					{
						if (root.isString())
						{
							for (Path folder : candidates(root.stringValue(), layer.folder(), "permissions." +
								profile.getKey() + ".workspace_roots", layer.source()).keySet())
							{
								add(folder, "permissions." + profile.getKey() + ".workspace_roots in " + layer.source(),
									false);
							}
						}
					}
				}
			}
		}

		/**
		 * Adds the folder that a subpath of a permission entry names.
		 *
		 * @param base    the folder that the entry grants
		 * @param subpath the subpath
		 * @param setting what grants it
		 */
		private void addSubpath(Path base, String subpath, String setting)
		{
			try
			{
				add(base.resolve(subpath).normalize(), setting, false);
			}
			catch (InvalidPathException _)
			{
				add(base, setting, false);
			}
		}

		/**
		 * Indicates whether an access value grants no write: it reads {@code read}, {@code deny} or {@code none}.
		 *
		 * @param access the value
		 * @return {@code true} if the value is one of them
		 */
		private static boolean isNoWrite(JsonNode access)
		{
			if (!access.isString())
				return false;
			String value = access.stringValue();
			return value.equals("read") || value.equals("deny") || value.equals("none");
		}

		/**
		 * Indicates whether an entry grants a write, in whole or in part.
		 *
		 * @param access the value of the entry
		 * @return {@code true} if it does
		 */
		private static boolean grantsWrite(JsonNode access)
		{
			if (access.isObject())
			{
				for (Entry<String, JsonNode> sub : access.properties())
				{
					if (!isNoWrite(sub.getValue()))
						return true;
				}
				return false;
			}
			return !isNoWrite(access);
		}

		/**
		 * Returns the folders that the key of a permission entry names.
		 *
		 * @param key   the key
		 * @param layer the layer that holds the entry
		 * @param name  the dotted name of the table that holds the entry
		 * @return the folders
		 */
		private List<Path> bases(String key, Layer layer, String name)
		{
			if (!key.startsWith(":"))
				return List.copyOf(candidates(key, layer.folder(), name, layer.source()).keySet());
			List<Path> bases = new ArrayList<>();
			switch (key)
			{
				case ":root" -> FileSystems.getDefault().getRootDirectories().forEach(bases::add);
				case ":workspace_roots", ":project_roots" -> bases.add(session.workingDirectory());
				case ":slash_tmp" -> session.slashTmp().ifPresent(bases::add);
				case ":tmpdir" ->
				{
					String tmpdir = session.environment().getOrDefault("TMPDIR", "");
					if (!tmpdir.isBlank())
					{
						try
						{
							Path folder = Path.of(tmpdir);
							if (folder.isAbsolute())
								bases.add(folder.normalize());
						}
						catch (InvalidPathException _)
						{
							// Codex ignores a TMPDIR that is not a path
						}
					}
				}
				default ->
				{
					// Codex ignores a special path that it does not know
				}
			}
			return bases;
		}

		/**
		 * Resolves a path of a setting to the folders that it may name.
		 *
		 * @param text   the path
		 * @param folder the folder that a relative path may be relative to, or null
		 * @param name   the setting, named in a problem
		 * @param source where the setting is
		 * @return each folder, mapped to whether it is certain to be the one that Codex means
		 */
		private Map<Path, Boolean> candidates(String text, Path folder, String name, String source)
		{
			Map<Path, Boolean> candidates = new LinkedHashMap<>();
			try
			{
				if (text.equals("~") || text.startsWith("~/"))
				{
					candidates.put(session.userHome().resolve(text.substring(1).replaceAll("^/+", "")).normalize(),
						true);
					return candidates;
				}
				Path path = Path.of(text);
				if (path.isAbsolute())
				{
					candidates.put(path.normalize(), true);
					return candidates;
				}
				candidates.put(session.workingDirectory().resolve(path).normalize(), false);
				if (folder != null)
					candidates.put(folder.resolve(path).normalize(), false);
			}
			catch (InvalidPathException e)
			{
				problems.add(name + " in " + source + " names \"" + text + "\", which is not a path: " + e.getReason());
			}
			return candidates;
		}

		/**
		 * Adds a grant, keeping a certain grant in preference to a possible one at the same folder.
		 *
		 * @param folder  the folder
		 * @param setting what grants it
		 * @param certain {@code true} if the grant is certain
		 */
		private void add(Path folder, String setting, boolean certain)
		{
			WriteGrant existing = grants.get(folder);
			if (existing != null && (!existing.possible() || !certain))
				return;
			grants.put(folder, new WriteGrant(folder, setting, !certain));
		}
	}
}
