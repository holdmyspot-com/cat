package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
	 * directory, as glob patterns.
	 */
	private static final List<String> ANCESTOR_INSTRUCTIONS = List.of("CLAUDE.md", "CLAUDE.local.md",
		".claude/CLAUDE.md", ".claude/rules/**");
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
	 * The resulting file retains the parent sandbox network and filesystem policies, the parent's permission rules
	 * that deny a tool or ask before using it, and CAT-owned environment values. It does not retain the parent's
	 * permission grants, such as its allow rules, added directories and default permission mode, so the isolated
	 * instance cannot use them to reach the parent's directories.
	 * Outside Windows, it requires a sandbox and forbids commands outside it, whatever the parent's settings say. On
	 * Windows, it retains all of the parent's sandbox settings instead and requires no sandbox. Host settings must not
	 * control other isolated-instance behavior. It also denies the instance reading each of
	 * {@code deniedReads}: the sandbox denies its commands through {@code sandbox.filesystem.denyRead}, and a
	 * {@code Read} rule in {@code permissions.deny} denies its file tools, which the sandbox does not confine. When
	 * {@code deniedReads} is not empty, the host's {@code sandbox.filesystem.allowRead} is not retained, because it
	 * would re-allow the reads that the sandbox denies.
	 * <p>
	 * Claude Code loads the instruction files of every directory above its working directory. A run directory can lie
	 * inside another project, such as the project whose prompts CAT measures, so the settings exclude every
	 * instruction file above {@code isolationRoot} through {@code claudeMdExcludes}.
	 *
	 * @param scope services shared for the runner process lifetime
	 * @param source the host Claude settings file
	 * @param destination the isolated Claude settings file
	 * @param claudeTemporaryDirectory the CAT-owned directory for Claude temporary files
	 * @param claudeHome the CAT-owned home directory for the isolated Claude process
	 * @param isolationRoot the directory above which the isolated instance loads no instruction file
	 * @param deniedReads the existing files and directories that the isolated instance must not read
	 * @param platform the platform that the isolated instance runs on
	 * @throws IOException if settings I/O fails
	 */
	public static void writeIsolatedSettings(HarnessRunnerScope scope, Path source, Path destination,
		Path claudeTemporaryDirectory, Path claudeHome, Path isolationRoot, List<Path> deniedReads, Platform platform)
		throws IOException
	{
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
		allowWritesToInvocationHome(filesystem, claudeHome);
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
			{
				ObjectNode permissions;
				if (settings.path("permissions").isObject())
					permissions = (ObjectNode) settings.get("permissions");
				else
					permissions = settings.putObject("permissions");
				permissions.set(restriction, rules.deepCopy());
			}
		}
	}

	/**
	 * Adds the invocation-local home to Claude's sandbox write policy.
	 *
	 * @param filesystem the isolated filesystem settings
	 * @param claudeHome the writable invocation-local home
	 */
	private static void allowWritesToInvocationHome(ObjectNode filesystem, Path claudeHome)
	{
		assert filesystem != null;
		assert claudeHome != null;
		JsonNode configuredPaths = filesystem.path("allowWrite");
		ArrayNode allowedPaths;
		if (configuredPaths.isArray())
			allowedPaths = (ArrayNode) configuredPaths;
		else
			allowedPaths = filesystem.putArray("allowWrite");
		String home = claudeHome.toString();
		for (JsonNode allowedPath : allowedPaths)
		{
			if (allowedPath.isString() && allowedPath.stringValue().equals(home))
				return;
		}
		allowedPaths.add(home);
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
		ObjectNode permissions;
		if (settings.path("permissions").isObject())
			permissions = (ObjectNode) settings.get("permissions");
		else
			permissions = settings.putObject("permissions");
		ArrayNode permissionDenials = array(permissions, "deny");
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
