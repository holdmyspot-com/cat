package io.github.cowwoc.cat.claude.runner;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.runner.HarnessEnvironment;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;

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
final class ClaudeConnectionSettings
{
	private static final int MAXIMUM_SETTINGS_BYTES = 1_048_576;
	private static final Set<String> CONNECTION_VARIABLES = Set.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
		"ANTHROPIC_BASE_URL", "CLAUDE_CODE_OAUTH_TOKEN");

	/**
	 * Copies approved connection variables from the parent process into an isolated environment.
	 *
	 * @param source the parent process environment
	 * @param destination the mutable isolated process environment
	 */
	static void copyProcessEnvironment(Map<String, String> source, Map<String, String> destination)
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
	static void copy(HarnessRunnerScope scope, Path source, Map<String, String> environment) throws IOException
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
	 * The resulting file retains the parent sandbox network and filesystem policies, the permissions that give the
	 * nested Claude instance access to its project and invocation-local home, and CAT-owned environment values.
	 * Host settings must not control other isolated-instance behavior. It also denies the instance reading each of
	 * {@code deniedReads}: the sandbox denies its commands through {@code sandbox.filesystem.denyRead}, and a
	 * {@code Read} rule in {@code permissions.deny} denies its file tools, which the sandbox does not confine. When
	 * {@code deniedReads} is not empty, the host's {@code sandbox.filesystem.allowRead} is not retained, because it
	 * would re-allow the reads that the sandbox denies.
	 *
	 * @param scope services shared for the runner process lifetime
	 * @param source the host Claude settings file
	 * @param destination the isolated Claude settings file
	 * @param claudeTemporaryDirectory the CAT-owned directory for Claude temporary files
	 * @param claudeHome the CAT-owned home directory for the isolated Claude process
	 * @param deniedReads the existing files and directories that the isolated instance must not read
	 * @throws IOException if settings I/O fails
	 */
	static void writeIsolatedSettings(HarnessRunnerScope scope, Path source, Path destination,
		Path claudeTemporaryDirectory, Path claudeHome, List<Path> deniedReads) throws IOException
	{
		JsonMapper mapper = scope.json();
		ObjectNode settings = mapper.createObjectNode();
		ObjectNode environment = settings.putObject("env");
		environment.put("DISABLE_AUTOUPDATER", "1");
		environment.put("CLAUDE_CODE_TMPDIR", claudeTemporaryDirectory.toString());
		ObjectNode sandbox = settings.putObject("sandbox");
		sandbox.put("enabled", true);
		sandbox.put("failIfUnavailable", true);
		sandbox.put("allowUnsandboxedCommands", false);
		ObjectNode filesystem = sandbox.putObject("filesystem");
		if (Files.isRegularFile(source))
		{
			JsonNode parentSettings = mapper.readTree(BoundedInput.readUtf8(source, MAXIMUM_SETTINGS_BYTES,
				"Claude settings"));
			JsonNode parentSandbox = parentSettings.path("sandbox");
			JsonNode network = parentSandbox.path("network");
			JsonNode parentFilesystem = parentSandbox.path("filesystem");
			if (network.isObject())
				sandbox.set("network", network.deepCopy());
			if (parentFilesystem.isObject())
			{
				filesystem = (ObjectNode) parentFilesystem.deepCopy();
				sandbox.set("filesystem", filesystem);
			}
			JsonNode parentPermissions = parentSettings.path("permissions");
			if (parentPermissions.isObject())
				settings.set("permissions", parentPermissions.deepCopy());
		}
		allowWritesToInvocationHome(filesystem, claudeHome);
		denyReads(settings, filesystem, deniedReads);

		Files.createDirectories(claudeTemporaryDirectory);
		Files.createDirectories(claudeHome);
		Files.createDirectories(destination.getParent());
		Files.writeString(destination, mapper.writeValueAsString(settings));
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
			permissionDenials.add("Read(/" + deniedRead + descendants + ")");
		}
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
