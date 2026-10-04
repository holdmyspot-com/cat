package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.BoundedInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copies the connection settings needed by an isolated Codex process without inheriting host customization state.
 */
final class CodexConnectionConfiguration
{
	private static final int MAXIMUM_CONFIGURATION_BYTES = 1_048_576;
	private static final String STARTUP_UPDATE_CHECK = "check_for_update_on_startup = false";
	static final String RUNNER_PERMISSION_PROFILE = "cat-runner";
	private static final String VALIDATOR_PERMISSION_PROFILE = "cat-validator";
	private static final String HELD_OUT_PERMISSION_PROFILE = "cat-held-out";
	private static final String CAT_PERMISSION_PROFILE = "cat";
	private static final String TEMPORARY_DIRECTORY = ":tmpdir";
	private static final Set<String> CONNECTION_SETTINGS = Set.of("forced_login_method", "model",
		"model_reasoning_effort", "openai_base_url");
	private static final Pattern NETWORK_ACCESS = Pattern.compile("^network_access\\s*=\\s*(true|false)\\s*(?:#.*)?$");
	private static final Pattern CAT_PERMISSION_SECTION = Pattern.compile("^\\[permissions\\.cat(?:[.].*)?]\\s*$");
	static final List<String> CAT_HOOK_KEYS = List.of("cat@cat:hooks/hooks.json:session_start:0:0",
		"cat@cat:hooks/hooks.json:subagent_start:0:0");
	private static final Pattern TRUSTED_HASH = Pattern.compile(
		"(?m)^trusted_hash\\s*=\\s*\\\"(sha256:[0-9a-f]{64})\\\"\\s*$");

	/**
	 * Copies supported top-level connection settings from a host Codex configuration and disables Codex's startup update
	 * check in the isolated configuration.
	 *
	 * @param source the host configuration file
	 * @param destination the isolated configuration file
	 * @throws IOException if configuration I/O fails
	 */
	static void copy(Path source, Path destination) throws IOException
	{
		copy(source, destination, null, destination.getParent().resolve("tmp"));
	}

	/**
	 * Copies supported connection settings and makes one verified npm cache available to nested shell commands.
	 *
	 * <p>
	 * Codex starts its own sandboxed shell processes. Passing a cache variable to the app server alone does not make
	 * it available to those shells, so a plain {@code npm install} can instead select the read-only default cache and
	 * fail before it reaches the registry. This configuration gives every shell the verified cache path and grants that
	 * path the matching workspace-write authority.
	 *
	 * @param source the host configuration file
	 * @param destination the isolated configuration file
	 * @param npmCache the verified writable npm cache, or {@code null} when no nested package acquisition is needed
	 * @param temporaryDirectory the fresh temporary directory owned by the isolated runner
	 * @throws IOException if configuration I/O fails
	 */
	static void copy(Path source, Path destination, Path npmCache, Path temporaryDirectory) throws IOException
	{
		requireThat(temporaryDirectory, "temporaryDirectory").isNotNull();
		StringBuilder copied = new StringBuilder(256).append(STARTUP_UPDATE_CHECK).append(System.lineSeparator()).
			append("default_permissions = \"").append(RUNNER_PERMISSION_PROFILE).append('"').
			append(System.lineSeparator());
		String networkAccess = null;
		if (Files.isRegularFile(source))
		{
			String configuration = BoundedInput.readUtf8(source, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration");
			for (String line : configuration.lines().toList())
			{
				String trimmed = line.stripLeading();
				if (trimmed.startsWith("["))
					break;
				int equals = trimmed.indexOf('=');
				if (equals < 1)
					continue;
				String name = trimmed.substring(0, equals).strip();
				if (CONNECTION_SETTINGS.contains(name))
				copied.append(line).append(System.lineSeparator());
			}

			networkAccess = workspaceWriteNetworkAccess(configuration);
			copyCatPermissions(configuration, copied);
		}
		if (networkAccess != null || npmCache != null)
		{
			copied.append(System.lineSeparator()).append("[sandbox_workspace_write]").append(System.lineSeparator());
			if (networkAccess != null)
				copied.append("network_access = ").append(networkAccess).append(System.lineSeparator());
			if (npmCache != null)
				copied.append("writable_roots = [\"").append(toml(npmCache)).append("\"]").append(System.lineSeparator());
		}
		if (npmCache != null)
		{
			copied.append(System.lineSeparator()).append("[shell_environment_policy.set]").append(System.lineSeparator()).
				append("NPM_CONFIG_CACHE = \"").append(toml(npmCache)).append('"').append(System.lineSeparator());
		}
		else
			copied.append(System.lineSeparator()).append("[shell_environment_policy.set]").append(System.lineSeparator());
		copied.append("TMPDIR = \"").append(toml(temporaryDirectory)).append('"').append(System.lineSeparator());
		appendRunnerProfile(copied, destination.getParent(), npmCache, temporaryDirectory);
		Files.createDirectories(destination.getParent());
		Files.writeString(destination, copied.toString());
	}

	/**
	 * Gives the nested process's own runtime state the same narrowly scoped write authority as its project workspace.
	 *
	 * <p>
	 * The harness shell starts from the copied project, while the runner deliberately puts its credentials, session
	 * state, plugins, and configuration in a sibling isolated home. A workspace-only profile cannot initialize that
	 * shell because its required home lies outside the workspace. The child profile preserves the parent's CAT policy
	 * when available and adds only the newly created isolated home, the harness temporary directory, and the shared
	 * dependency cache. Codex's Linux sandbox creates its process lock and scratch state under the harness temporary
	 * directory before a shell command can run, so it needs the same temporary-directory authority as the parent
	 * workspace-write sandbox. Its temporary directory is created inside the runner-owned home beneath CAT work storage,
	 * rather than granting global {@code /tmp}; the harness sandbox uses it before the first shell command can run.
	 *
	 * @param configuration the isolated configuration being assembled
	 * @param isolatedHome the fresh runtime home that the runner owns
	 * @param npmCache the verified shared dependency cache, or {@code null} when no cache was supplied
	 * @param temporaryDirectory the fresh harness temporary directory owned by the runner
	 */
	private static void appendRunnerProfile(StringBuilder configuration, Path isolatedHome, Path npmCache,
		Path temporaryDirectory)
	{
		assert configuration != null;
		assert isolatedHome != null;
		assert temporaryDirectory != null;
		String parentProfile = ":workspace";
		if (configuration.indexOf("[permissions." + CAT_PERMISSION_PROFILE + "]") >= 0)
			parentProfile = CAT_PERMISSION_PROFILE;
		configuration.append(System.lineSeparator()).append("[permissions.").append(RUNNER_PERMISSION_PROFILE).
			append(']').append(System.lineSeparator()).append("extends = \"").append(parentProfile).append('"').
			append(System.lineSeparator()).append("[permissions.").append(RUNNER_PERMISSION_PROFILE).
			append(".filesystem]").append(System.lineSeparator()).append('"').append(toml(isolatedHome)).
			append("\" = \"write\"").append(System.lineSeparator()).append('"').append(TEMPORARY_DIRECTORY).
			append("\" = \"write\"").
			append(System.lineSeparator());
		if (npmCache != null)
			configuration.append('"').append(toml(npmCache)).append("\" = \"write\"").append(System.lineSeparator());
	}

	/**
	 * Escapes one filesystem path for a TOML basic string.
	 *
	 * @param path the path to render
	 * @return a TOML-safe absolute path value
	 */
	private static String toml(Path path)
	{
		assert path != null;
		return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/**
	 * Adds the permission profile that a session's thread runs under.
	 *
	 * @param destination the isolated Codex configuration
	 * @param readOnlyEvidence the completed evidence directories and files that the session may read but not change
	 * @param deniedReads the existing files and directories that the session must not read
	 * @return the profile that the thread names, or an empty string if the thread uses the workspace-write sandbox
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the isolated configuration cannot be updated
	 */
	static String addSessionProfile(Path destination, List<Path> readOnlyEvidence, List<Path> deniedReads)
		throws IOException
	{
		requireThat(destination, "destination").isNotNull();
		requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		String profile = "";
		if (!readOnlyEvidence.isEmpty())
		{
			addValidatorProfile(destination, readOnlyEvidence);
			profile = VALIDATOR_PERMISSION_PROFILE;
		}
		if (deniedReads.isEmpty())
			return profile;
		if (profile.isEmpty())
			profile = RUNNER_PERMISSION_PROFILE;
		addHeldOutProfile(destination, profile, deniedReads);
		return HELD_OUT_PERMISSION_PROFILE;
	}

	/**
	 * Adds the profile that denies a session reading files and directories.
	 *
	 * @param destination the isolated Codex configuration
	 * @param parentProfile the profile whose permissions the session otherwise has
	 * @param deniedReads the files and directories to deny; a path that no longer exists is skipped
	 * @throws IOException if the isolated configuration cannot be updated
	 */
	private static void addHeldOutProfile(Path destination, String parentProfile, List<Path> deniedReads)
		throws IOException
	{
		String configuration = BoundedInput.readUtf8(destination, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration");
		if (!configuration.contains("[permissions." + RUNNER_PERMISSION_PROFILE + "]"))
			throw new IOException("Denying reads requires the CAT runner permission profile");
		String section = "[permissions." + HELD_OUT_PERMISSION_PROFILE;
		StringBuilder profile = new StringBuilder(configuration).append(System.lineSeparator()).append(section).
			append(']').append(System.lineSeparator()).append("extends = \"").append(parentProfile).append('"').
			append(System.lineSeparator()).append(section).append(".filesystem]").append(System.lineSeparator());
		for (Path deniedRead : deniedReads)
		{
			// Codex's Linux sandbox creates an empty placeholder at a missing denied path while the session runs, which
			// would block CAT from creating that path. Denying the real path also covers every symbolic-link alias.
			if (Files.notExists(deniedRead))
				continue;
			profile.append('"').append(toml(deniedRead.toRealPath())).append("\" = \"deny\"").
				append(System.lineSeparator());
		}

		// A thread that names a profile ignores [sandbox_workspace_write], and the built-in ":workspace" profile that
		// the runner profile extends when the host has no CAT profile denies network access. Copy the parent's explicit
		// workspace-write network policy so that denying reads does not also change network access.
		String networkAccess = workspaceWriteNetworkAccess(configuration);
		if (networkAccess != null && !configuration.contains("[permissions." + CAT_PERMISSION_PROFILE + "]"))
		{
			profile.append(section).append(".network]").append(System.lineSeparator()).append("enabled = ").
				append(networkAccess).append(System.lineSeparator());
		}
		Files.writeString(destination, profile.toString());
	}

	/**
	 * Adds the profile used when an independent validator reads completed executor evidence.
	 *
	 * @param destination the isolated Codex configuration
	 * @param evidenceRoots the completed evidence directories and files
	 * @throws IOException if the isolated configuration cannot be updated
	 */
	static void addValidatorProfile(Path destination, List<Path> evidenceRoots) throws IOException
	{
		requireThat(evidenceRoots, "evidenceRoots").isNotEmpty();
		String configuration = BoundedInput.readUtf8(destination, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration");
		if (!configuration.contains("[permissions." + RUNNER_PERMISSION_PROFILE + "]"))
			throw new IOException("Read-only validator evidence requires the CAT runner permission profile");
		// A validator's verdict is credible only if the harness can read executor evidence but cannot rewrite it.
		Files.writeString(destination, configuration + System.lineSeparator() + "[permissions." +
			VALIDATOR_PERMISSION_PROFILE + "]" + System.lineSeparator() + "extends = \"" + RUNNER_PERMISSION_PROFILE +
			"\"" + System.lineSeparator() + "[permissions." + VALIDATOR_PERMISSION_PROFILE + ".filesystem]" +
			System.lineSeparator() + evidencePermissions(evidenceRoots));
	}

	/**
	 * Renders the exact read-only authority needed for completed evidence.
	 *
	 * @param evidenceRoots the completed evidence directories and files
	 * @return TOML filesystem permission entries
	 * @throws IOException if an evidence entry cannot be canonicalized
	 */
	private static String evidencePermissions(List<Path> evidenceRoots) throws IOException
	{
		StringBuilder result = new StringBuilder();
		for (Path evidenceRoot : evidenceRoots)
		{
			String quotedEvidenceRoot = evidenceRoot.toRealPath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
			result.append('"').append(quotedEvidenceRoot).append("\" = \"read\"").append(System.lineSeparator());
		}
		return result.toString();
	}

	/**
	 * Copies the parent's CAT permission policy so the isolated process receives the same sandbox filesystem authority.
	 *
	 * @param configuration the complete parent configuration
	 * @param destination the isolated configuration being assembled
	 */
	private static void copyCatPermissions(String configuration, StringBuilder destination)
	{
		assert configuration != null;
		assert destination != null;
		boolean copying = false;
		for (String line : configuration.lines().toList())
		{
			String trimmed = line.strip();
			if (trimmed.startsWith("["))
				copying = CAT_PERMISSION_SECTION.matcher(trimmed).matches();
			if (copying)
				destination.append(line).append(System.lineSeparator());
		}
	}

	/**
	 * Extracts the parent workspace-write sandbox's explicit network-access policy.
	 *
	 * @param configuration the complete parent Codex configuration
	 * @return {@code true} or {@code false} when the parent declares the policy, or {@code null} when it does not
	 */
	private static String workspaceWriteNetworkAccess(String configuration)
	{
		assert configuration != null;
		boolean inWorkspaceWriteSandbox = false;
		for (String line : configuration.lines().toList())
		{
			String trimmed = line.strip();
			if (trimmed.startsWith("["))
			{
				inWorkspaceWriteSandbox = trimmed.equals("[sandbox_workspace_write]");
				continue;
			}

			if (!inWorkspaceWriteSandbox)
				continue;
			Matcher networkAccess = NETWORK_ACCESS.matcher(trimmed);
			if (networkAccess.matches())
				return networkAccess.group(1);
		}
		return null;
	}

	/**
	 * Copies the host's existing approvals for CAT's context-injection hooks into an isolated Codex home.
	 * <p>
	 * The copied value is only an expected hook hash. Codex compares it against the installed artifact's current hook
	 * hash before execution, so a changed hook remains untrusted.
	 *
	 * @param source the host Codex configuration file
	 * @param destination the isolated Codex configuration file
	 * @throws IOException if configuration I/O fails
	 */
	static void copyTrustedCatHooks(Path source, Path destination) throws IOException
	{
		if (!Files.isRegularFile(source))
			return;
		String configuration = BoundedInput.readUtf8(source, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration");
		StringBuilder approvals = new StringBuilder(256);
		for (String key : CAT_HOOK_KEYS)
		{
			Pattern statePattern = Pattern.compile("(?ms)^\\[hooks\\.state\\.\\\"" + Pattern.quote(key) +
				"\\\"]\\R(.*?)(?=^\\[|\\z)");
			Matcher state = statePattern.matcher(configuration);
			if (!state.find())
				continue;
			Matcher hash = TRUSTED_HASH.matcher(state.group(1));
			if (!hash.find())
				continue;
			approvals.append(System.lineSeparator()).append("[hooks.state.\"").append(key).append("\"]").
				append(System.lineSeparator()).append("trusted_hash = \"").append(hash.group(1)).append('"').
				append(System.lineSeparator());
		}
		if (approvals.isEmpty())
			return;
		Files.createDirectories(destination.getParent());
		Files.writeString(destination, approvals.toString(), java.nio.file.StandardOpenOption.CREATE,
			java.nio.file.StandardOpenOption.APPEND);
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private CodexConnectionConfiguration()
	{
	}
}
