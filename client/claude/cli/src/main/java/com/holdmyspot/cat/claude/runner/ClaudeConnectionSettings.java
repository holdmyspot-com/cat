package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Supplies allow-listed Claude connection variables from the parent process and its settings file.
 */
public final class ClaudeConnectionSettings
{
	private static final int MAXIMUM_SETTINGS_BYTES = 1_048_576;
	/**
	 * The instruction files that Claude Code loads from each directory above its working directory, relative to that
	 * directory, as glob patterns. They include the {@code AGENTS.md} files that Claude Code loads when the project has
	 * no {@code CLAUDE.md} of its own.
	 */
	private static final List<String> ANCESTOR_INSTRUCTIONS = List.of("CLAUDE.md", "CLAUDE.local.md",
		".claude/CLAUDE.md", ".claude/rules/**", "AGENTS.md", ".claude/AGENTS.md");
	/**
	 * The identifier of Claude Code's built-in plugin that decides which instruction files load.
	 */
	private static final String INSTRUCTION_FILES_PLUGIN = "cc-plugin-agents-md@builtin";
	/**
	 * The option of {@link #INSTRUCTION_FILES_PLUGIN} that decides which instruction files load.
	 */
	private static final String INSTRUCTION_FILES_OPTION = "instructionFiles";
	/**
	 * The characters that a glob pattern treats as syntax.
	 */
	private static final String GLOB_SYNTAX = "\\*?[]{}()!+@";
	/**
	 * The lists of rules in Claude Code's {@code permissions} settings that restrict tools: those that deny a tool and
	 * those that ask the user before using it. The isolated settings copy these from the parent and no other
	 * permission setting.
	 */
	private static final List<String> PERMISSION_RESTRICTIONS = List.of("deny", "ask");
	/**
	 * The permission rules that the isolated settings allow, whatever the parent's settings say. Claude Code asks the
	 * user before using the {@code Skill} tool, and a nested instance has no user to answer, so without this rule a
	 * trial could use none of the skills of the prompt system it measures. Removing credentials from commands makes
	 * Claude Code ignore the {@code acceptEdits} permission mode, so the rule for editing tools grants the same edits
	 * inside the project and nowhere else.
	 */
	private static final List<String> PERMISSION_GRANTS = List.of("Skill", "Edit(./**)");
	private static final Set<String> CONNECTION_VARIABLES = Set.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
		"ANTHROPIC_BASE_URL", "CLAUDE_CODE_OAUTH_TOKEN");

	/**
	 * Copies approved connection variables from the parent process into an isolated environment.
	 *
	 * @param source the parent process environment
	 * @param destination the mutable isolated process environment
	 */
	public static void copyProcessEnvironment(Map<String, String> source, Map<String, String> destination)
	{
		HarnessEnvironment.copyAllowed(source, destination, CONNECTION_VARIABLES);
	}

	/**
	 * Copies absent connection variables from a host Claude settings file into an isolated process environment.
	 *
	 * @param scope services shared for the runner process lifetime
	 * @param source the host settings file
	 * @param environment the mutable isolated process environment
	 * @throws IOException if the settings file cannot be read
	 */
	public static void copy(HarnessRunnerScope scope, Path source, Map<String, String> environment) throws IOException
	{
		if (!Files.isRegularFile(source))
			return;
		JsonNode configured = scope.json().readTree(BoundedInput.readUtf8(source, MAXIMUM_SETTINGS_BYTES,
			"Claude settings")).path("env");
		if (!configured.isObject())
			return;
		for (String variable : CONNECTION_VARIABLES)
		{
			JsonNode value = configured.path(variable);
			if (!environment.containsKey(variable) && value.isString() && !value.stringValue().isBlank())
				environment.put(variable, value.stringValue());
		}
	}

	/**
	 * Writes settings for an isolated Claude instance.
	 *
	 * <p>
	 * The resulting file retains the parent sandbox network and filesystem policies, except the paths that the parent
	 * allows writing, the parent's permission rules that deny a tool or ask before using it, and CAT-owned environment
	 * values. The instance may write only to {@code claudeHome} and {@code dependencyCaches}, not to the parent's
	 * writable paths, such as its Claude and CAT directories. It does not retain the parent's permission grants, such
	 * as its allow rules, added directories and default permission mode, so the isolated instance cannot use them to
	 * reach the parent's directories. It allows the {@code Skill} tool instead, which the isolated instance has no
	 * user to approve.
	 * Outside Windows, it requires a sandbox and forbids commands outside it, whatever the parent's settings say. On
	 * Windows, it retains the parent's other sandbox settings instead and requires no sandbox. Host settings must not
	 * control other isolated-instance behavior. It also denies the instance reading each of
	 * {@code deniedReads}: the sandbox denies its commands through {@code sandbox.filesystem.denyRead}, and a
	 * {@code Read} rule in {@code permissions.deny} denies its file tools, which the sandbox does not confine. When
	 * {@code deniedReads} is not empty, the host's {@code sandbox.filesystem.allowRead} is not retained, because it
	 * would re-allow the reads that the sandbox denies.
	 * <p>
	 * Claude Code loads the instruction files of every directory above its working directory. A run directory can lie
	 * inside another project, such as the project whose prompts CAT measures, so the settings exclude every
	 * instruction file above {@code isolationRoot} through {@code claudeMdExcludes}. The settings keep the parent's
	 * choice of which instruction files load, such as {@code AGENTS.md} beside or in place of {@code CLAUDE.md}, so
	 * that the isolated instance loads the instruction files that the parent's sessions load.
	 *
	 * @param scope services shared for the runner process lifetime
	 * @param source the host Claude settings file
	 * @param destination the isolated Claude settings file
	 * @param claudeTemporaryDirectory the CAT-owned directory for Claude temporary files
	 * @param claudeHome the CAT-owned home directory for the isolated Claude process
	 * @param dependencyCaches the parent's shared dependency caches that the isolated instance may write
	 * @param isolationRoot the directory above which the isolated instance loads no instruction file
	 * @param deniedReads the existing files and directories that the isolated instance must not read
	 * @param platform the platform that the isolated instance runs on
	 * @throws IOException if settings I/O fails
	 */
	public static void writeIsolatedSettings(HarnessRunnerScope scope, Path source, Path destination,
		Path claudeTemporaryDirectory, Path claudeHome, List<Path> dependencyCaches, Path isolationRoot,
		List<Path> deniedReads, Platform platform)
		throws IOException
	{
		writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory, claudeHome, dependencyCaches,
			isolationRoot, deniedReads, "", platform);
	}

	/**
	 * Writes settings for an isolated Claude instance, as
	 * {@link #writeIsolatedSettings(HarnessRunnerScope, Path, Path, Path, Path, List, Path, List, Platform)} does,
	 * except that {@code instructionFilesMode}, when it is not empty, decides which instruction files the instance
	 * loads in place of the parent's choice.
	 *
	 * @param scope                    services shared for the runner process lifetime
	 * @param source                   the host Claude settings file
	 * @param destination              the isolated Claude settings file
	 * @param claudeTemporaryDirectory the CAT-owned directory for Claude temporary files
	 * @param claudeHome               the CAT-owned home directory for the isolated Claude process
	 * @param dependencyCaches         the parent's shared dependency caches that the isolated instance may write
	 * @param isolationRoot            the directory above which the isolated instance loads no instruction file
	 * @param deniedReads              the existing files and directories that the isolated instance must not read
	 * @param instructionFilesMode     the choice of which instruction files load, such as {@code claude-md}, or an
	 *                                 empty string to keep the parent's choice
	 * @param platform                 the platform that the isolated instance runs on
	 * @throws NullPointerException if {@code instructionFilesMode} is null
	 * @throws IOException          if settings I/O fails
	 */
	public static void writeIsolatedSettings(HarnessRunnerScope scope, Path source, Path destination,
		Path claudeTemporaryDirectory, Path claudeHome, List<Path> dependencyCaches, Path isolationRoot,
		List<Path> deniedReads, String instructionFilesMode, Platform platform)
		throws IOException
	{
		writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory, claudeHome, dependencyCaches,
			isolationRoot, deniedReads, instructionFilesMode, null, platform);
	}

	/**
	 * Writes settings for an isolated Claude instance, as
	 * {@link #writeIsolatedSettings(HarnessRunnerScope, Path, Path, Path, Path, List, Path, List, String, Platform)}
	 * does, except that the sandbox also lets the instance's commands write the run's requests directory when the
	 * session may request sessions.
	 *
	 * @param scope                    services shared for the runner process lifetime
	 * @param source                   the host Claude settings file
	 * @param destination              the isolated Claude settings file
	 * @param claudeTemporaryDirectory the CAT-owned directory for Claude temporary files
	 * @param claudeHome               the CAT-owned home directory for the isolated Claude process
	 * @param dependencyCaches         the parent's shared dependency caches that the isolated instance may write
	 * @param isolationRoot            the directory above which the isolated instance loads no instruction file
	 * @param deniedReads              the existing files and directories that the isolated instance must not read
	 * @param instructionFilesMode     the choice of which instruction files load, such as {@code claude-md}, or an
	 *                                 empty string to keep the parent's choice
	 * @param requestAccess            the session's access to requested sessions, or {@code null} if it may request
	 *                                 none
	 * @param platform                 the platform that the isolated instance runs on
	 * @throws NullPointerException if {@code instructionFilesMode} is null
	 * @throws IOException          if settings I/O fails
	 */
	public static void writeIsolatedSettings(HarnessRunnerScope scope, Path source, Path destination,
		Path claudeTemporaryDirectory, Path claudeHome, List<Path> dependencyCaches, Path isolationRoot,
		List<Path> deniedReads, String instructionFilesMode, SessionRequestAccess requestAccess, Platform platform)
		throws IOException
	{
		requireThat(instructionFilesMode, "instructionFilesMode").isNotNull();
		JsonMapper mapper = scope.json();
		ObjectNode settings = mapper.createObjectNode();
		ObjectNode environment = settings.putObject("env");
		environment.put("DISABLE_AUTOUPDATER", "1");
		environment.put("CLAUDE_CODE_TMPDIR", claudeTemporaryDirectory.toString());
		ObjectNode sandbox = settings.putObject("sandbox");
		JsonNode parentSettings = mapper.missingNode();
		if (Files.isRegularFile(source))
		{
			parentSettings = mapper.readTree(BoundedInput.readUtf8(source, MAXIMUM_SETTINGS_BYTES,
				"Claude settings"));
		}
		JsonNode parentSandbox = parentSettings.path("sandbox");
		ObjectNode filesystem;
		if (platform.isWindows())
		{
			// Claude Code has no sandbox on Windows, so requiring one makes the nested instance exit at startup. The
			// nested instance runs under the parent's own sandbox settings instead, as the parent does.
			if (parentSandbox.isObject())
			{
				sandbox = (ObjectNode) parentSandbox.deepCopy();
				settings.set("sandbox", sandbox);
			}
			JsonNode parentFilesystem = sandbox.path("filesystem");
			if (parentFilesystem.isObject())
				filesystem = (ObjectNode) parentFilesystem;
			else
				filesystem = sandbox.putObject("filesystem");
		}
		else
		{
			sandbox.put("enabled", true);
			sandbox.put("failIfUnavailable", true);
			sandbox.put("allowUnsandboxedCommands", false);
			filesystem = sandbox.putObject("filesystem");
			JsonNode network = parentSandbox.path("network");
			JsonNode parentFilesystem = parentSandbox.path("filesystem");
			if (network.isObject())
				sandbox.set("network", network.deepCopy());
			if (parentFilesystem.isObject())
			{
				filesystem = (ObjectNode) parentFilesystem.deepCopy();
				sandbox.set("filesystem", filesystem);
			}
		}
		copyPermissionRestrictions(parentSettings.path("permissions"), settings);
		if (instructionFilesMode.isEmpty())
			copyInstructionFilesMode(parentSettings, settings);
		else
			putInstructionFilesMode(settings, instructionFilesMode);
		permissions(settings).set("allow", mapper.valueToTree(PERMISSION_GRANTS));
		List<String> writablePaths = new ArrayList<>();
		writablePaths.add(claudeHome.toString());
		for (Path cache : dependencyCaches)
			writablePaths.add(cache.toString());
		if (requestAccess != null)
			writablePaths.add(requestAccess.requests().toString());
		filesystem.set("allowWrite", mapper.valueToTree(writablePaths));
		denyReads(settings, filesystem, deniedReads);
		excludeInstructionsAbove(settings, isolationRoot, platform);

		Files.createDirectories(claudeTemporaryDirectory);
		Files.createDirectories(claudeHome);
		Files.createDirectories(destination.getParent());
		Files.writeString(destination, mapper.writeValueAsString(settings));
	}

	/**
	 * Copies the parent's rules that deny a tool or ask before using it. The parent's grants, such as its allow rules,
	 * added directories and default permission mode, are not copied: they would let the isolated instance read and
	 * write the parent's directories.
	 *
	 * @param parentPermissions the parent's {@code permissions} settings, or a missing node if it has none
	 * @param settings          the isolated settings
	 */
	private static void copyPermissionRestrictions(JsonNode parentPermissions, ObjectNode settings)
	{
		assert parentPermissions != null;
		assert settings != null;
		for (String restriction : PERMISSION_RESTRICTIONS)
		{
			JsonNode rules = parentPermissions.path(restriction);
			if (rules.isArray())
				permissions(settings).set(restriction, rules.deepCopy());
		}
	}

	/**
	 * Returns the choice of which instruction files Claude Code loads that a settings file makes, such as whether it
	 * loads {@code AGENTS.md} beside or in place of {@code CLAUDE.md}.
	 *
	 * @param json   reads the settings
	 * @param source the settings file
	 * @return the choice, or empty if the file does not exist or makes none
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if the file cannot be read or is not JSON
	 */
	public static Optional<String> instructionFilesMode(JsonMapper json, Path source) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(source, "source").isNotNull();
		if (!Files.isRegularFile(source))
			return Optional.empty();
		return instructionFilesMode(json.readTree(BoundedInput.readUtf8(source, MAXIMUM_SETTINGS_BYTES,
			"Claude settings")));
	}

	/**
	 * Returns the choice of which instruction files Claude Code loads that settings make.
	 *
	 * @param settings the settings, or a missing node if there are none
	 * @return the choice, or empty if the settings make none
	 */
	private static Optional<String> instructionFilesMode(JsonNode settings)
	{
		assert settings != null;
		JsonNode mode = settings.path("pluginConfigs").path(INSTRUCTION_FILES_PLUGIN).path("options").
			path(INSTRUCTION_FILES_OPTION);
		if (!mode.isString())
			return Optional.empty();
		return Optional.of(mode.stringValue());
	}

	/**
	 * Copies the parent's choice of which instruction files Claude Code loads, such as whether it loads
	 * {@code AGENTS.md} beside or in place of {@code CLAUDE.md}, so that the isolated instance loads the instruction
	 * files that the parent's sessions load. No other plugin configuration is copied. Does nothing if the parent makes
	 * no such choice.
	 *
	 * @param parentSettings the parent's settings, or a missing node if it has none
	 * @param settings       the isolated settings
	 */
	private static void copyInstructionFilesMode(JsonNode parentSettings, ObjectNode settings)
	{
		assert parentSettings != null;
		assert settings != null;
		Optional<String> mode = instructionFilesMode(parentSettings);
		if (mode.isEmpty())
			return;
		putInstructionFilesMode(settings, mode.get());
	}

	/**
	 * Sets the choice of which instruction files Claude Code loads.
	 *
	 * @param settings the isolated settings
	 * @param mode     the choice
	 */
	private static void putInstructionFilesMode(ObjectNode settings, String mode)
	{
		assert settings != null;
		assert mode != null;
		settings.putObject("pluginConfigs").putObject(INSTRUCTION_FILES_PLUGIN).putObject("options").
			put(INSTRUCTION_FILES_OPTION, mode);
	}

	/**
	 * Returns the {@code permissions} settings of the isolated settings, adding them if they are absent.
	 *
	 * @param settings the isolated settings
	 * @return the permission settings
	 */
	private static ObjectNode permissions(ObjectNode settings)
	{
		assert settings != null;
		if (settings.path("permissions").isObject())
			return (ObjectNode) settings.get("permissions");
		return settings.putObject("permissions");
	}

	/**
	 * Denies the isolated instance reading files and directories, in addition to any reads that the parent denies,
	 * and removes {@code allowRead} from the sandbox filesystem settings so that it cannot re-allow them. Does
	 * nothing if {@code deniedReads} is empty.
	 *
	 * @param settings the isolated settings
	 * @param filesystem the isolated sandbox filesystem settings
	 * @param deniedReads the existing files and directories to deny
	 */
	private static void denyReads(ObjectNode settings, ObjectNode filesystem, List<Path> deniedReads)
	{
		assert settings != null;
		assert filesystem != null;
		assert deniedReads != null;
		if (deniedReads.isEmpty())
			return;
		filesystem.remove("allowRead");
		ArrayNode sandboxDenials = array(filesystem, "denyRead");
		ArrayNode permissionDenials = array(permissions(settings), "deny");
		for (Path deniedRead : deniedReads)
		{
			sandboxDenials.add(deniedRead.toString());
			// A rule path that starts with "//" is absolute; "**" also matches everything below a directory.
			String descendants;
			if (Files.isDirectory(deniedRead))
				descendants = "/**";
			else
				descendants = "";
			permissionDenials.add("Read(/" + rulePath(deniedRead) + descendants + ")");
		}
	}

	/**
	 * Excludes every instruction file that Claude Code would load from a directory above {@code isolationRoot}.
	 *
	 * @param settings      the isolated settings
	 * @param isolationRoot the directory above which no instruction file loads
	 * @param platform      the platform that the isolated instance runs on
	 */
	private static void excludeInstructionsAbove(ObjectNode settings, Path isolationRoot, Platform platform)
	{
		assert settings != null;
		assert isolationRoot != null;
		assert platform != null;
		ArrayNode excludes = settings.putArray("claudeMdExcludes");
		for (Path directory = isolationRoot.toAbsolutePath().getParent(); directory != null;
			directory = directory.getParent())
		{
			String prefix = globPrefix(directory, platform);
			for (String instruction : ANCESTOR_INSTRUCTIONS)
				excludes.add(prefix + instruction);
		}
	}

	/**
	 * Returns a directory as the start of a glob pattern that matches the files below it.
	 *
	 * @param directory an absolute directory
	 * @param platform  the platform that the isolated instance runs on
	 * @return the directory with its glob syntax escaped, ending with {@code /}
	 */
	private static String globPrefix(Path directory, Platform platform)
	{
		assert directory != null;
		assert platform != null;
		String text = directory.toString();
		StringBuilder prefix = new StringBuilder(text.length() + 1);
		if (platform.isWindows())
		{
			// A backslash escapes glob syntax, so a Windows path names its directories with forward slashes instead
			prefix.append(text.replace('\\', '/'));
		}
		else
		{
			for (char character : text.toCharArray())
			{
				if (GLOB_SYNTAX.indexOf(character) >= 0)
					prefix.append('\\');
				prefix.append(character);
			}
		}
		if (prefix.isEmpty() || prefix.charAt(prefix.length() - 1) != '/')
			prefix.append('/');
		return prefix.toString();
	}

	/**
	 * Returns the form in which a permission rule names an absolute path. Claude Code converts a Windows path to the
	 * form it has on POSIX file systems before matching it against a rule, so that {@code C:\Users\alice} becomes
	 * {@code /c/Users/alice}.
	 *
	 * @param path an absolute path
	 * @return the path that a rule names, starting with {@code /} and separating names with {@code /}
	 * @throws NullPointerException     if {@code path} is null
	 * @throws IllegalArgumentException if {@code path} is relative
	 */
	public static String rulePath(Path path)
	{
		requireThat(path, "path").isAbsolute();
		String root = path.getRoot().toString();
		if (root.equals("/"))
			return path.toString();
		String names = PortablePaths.text(path.getRoot().relativize(path));
		if (root.length() >= 2 && root.charAt(1) == ':')
			return "/" + Character.toLowerCase(root.charAt(0)) + "/" + names;
		return root.replace('\\', '/') + names;
	}

	/**
	 * Returns an object's array property, replacing a missing or non-array value with an empty array.
	 *
	 * @param parent the object
	 * @param name the property's name
	 * @return the array
	 */
	private static ArrayNode array(ObjectNode parent, String name)
	{
		assert parent != null;
		assert name != null;
		JsonNode value = parent.path(name);
		if (value.isArray())
			return (ArrayNode) value;
		return parent.putArray(name);
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private ClaudeConnectionSettings()
	{
	}
}
