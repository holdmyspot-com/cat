package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.RealPaths;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Supplies the allow-listed Claude variables that a nested Claude process inherits, its connection variables and
 * its subagent settings, from the parent process and its settings file.
 */
public final class ClaudeConnectionSettings
{
	private static final int MAXIMUM_SETTINGS_BYTES = 1_048_576;
	/**
	 * The most that the paths of the isolated settings' read denials and permission rules may total. Claude Code puts
	 * every denied path on the command line that starts a sandboxed command; once the denials total about 2 MB, which
	 * is the most that a Linux command line holds, it cannot sandbox commands, ignores the rule that allows them and
	 * asks the user to approve each one, which no one can answer in a nested session.
	 */
	private static final int MAXIMUM_DENIED_PATH_BYTES = 1_500_000;
	/**
	 * The variable that names the base URL of the model provider that Claude Code connects to.
	 */
	private static final String BASE_URL_VARIABLE = "ANTHROPIC_BASE_URL";
	/**
	 * The host that Claude Code connects to when {@link #BASE_URL_VARIABLE} is not set.
	 */
	private static final String DEFAULT_MODEL_HOST = "api.anthropic.com";
	/**
	 * The folder of Claude Code's managed settings on Linux.
	 */
	private static final Path LINUX_MANAGED_SETTINGS = Path.of("/etc/claude-code");
	/**
	 * The folder of Claude Code's managed settings on macOS.
	 */
	private static final Path MACOS_MANAGED_SETTINGS = Path.of("/Library/Application Support/ClaudeCode");
	/**
	 * The folder of Claude Code's managed settings on Windows.
	 */
	private static final Path WINDOWS_MANAGED_SETTINGS = Path.of("C:\\Program Files\\ClaudeCode");
	/**
	 * The folder of the Windows managed settings, as WSL reaches it. Claude Code also reads it on WSL when an
	 * administrator source asks it to, so CAT checks it on Linux too; it does not exist elsewhere.
	 */
	private static final Path WSL_WINDOWS_MANAGED_SETTINGS = Path.of("/mnt/c/Program Files/ClaudeCode");
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
	 * The permission rules that the isolated settings of a sandboxed session allow, whatever the parent's settings say.
	 * Claude Code asks the user before using the {@code Skill} tool, and a nested instance has no user to answer, so
	 * without this rule a trial could use none of the skills of the prompt system it measures. The rule for editing
	 * tools grants edits inside the project, and nowhere else. A session without a sandbox is offered neither tool.
	 */
	private static final List<String> PERMISSION_GRANTS = List.of("Skill", "Edit(./**)");
	/**
	 * The permission rule that lets the isolated instance run every Bash command without asking the user, which a
	 * nested instance has no user to answer. The rule does not depend on Claude Code's
	 * {@code sandbox.autoAllowBashIfSandboxed}, and the settings require the sandbox that confines the commands. The
	 * parent's rules that deny a command or ask before running it still take precedence over it. A session without a
	 * sandbox is offered no tool that runs a command, so its settings omit the rule.
	 */
	private static final String SANDBOXED_COMMAND_GRANT = "Bash";
	/**
	 * The permission rule that denies every tool that an MCP server provides, including the tools of the claude.ai
	 * connectors that Claude Code announces to a session from the user's account. Claude Code runs an MCP tool outside
	 * any sandbox, so the isolated settings of a session without a sandbox deny them by this rule, which matches the
	 * full name of each tool of every server.
	 */
	private static final String MCP_TOOLS_DENIAL = "mcp__*";
	/**
	 * The characters that a gitignore pattern treats as syntax and that can appear in a path: the escape character and
	 * the characters of a character class, a wildcard and a negation. A permission rule's path is a gitignore pattern,
	 * so a path that holds one matches the path itself only if the rule escapes the character with a backslash.
	 */
	private static final String RULE_PATH_SYNTAX = "\\[]*?!";
	/**
	 * The variables that a nested Claude process takes from its parent: the connection variables, and the variables
	 * that decide whether and how a session starts subagents, such as how many run at once, how deeply they nest,
	 * which model and effort they use, and whether the built-in, fork, plan, workflow and team agents are offered. A
	 * nested session therefore starts subagents the way its parent does. Claude Code has no other setting for this:
	 * it reads these from its environment, where a user sets them directly or through a settings file's {@code env}.
	 */
	private static final Set<String> INHERITED_VARIABLES = Set.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
		"ANTHROPIC_BASE_URL", "CLAUDE_CODE_OAUTH_TOKEN",
		"CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS", "CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH", "CLAUDE_CODE_SUBAGENT_MODEL",
		"CLAUDE_CODE_SUBAGENT_MODEL_FORCE", "CLAUDE_CODE_SUBAGENT_EFFORT", "CLAUDE_CODE_FORK_SUBAGENT",
		"CLAUDE_CODE_DISABLE_EXPLORE_PLAN_AGENTS", "CLAUDE_AGENT_SDK_DISABLE_BUILTIN_AGENTS",
		"CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS", "CLAUDE_CODE_PLAN_V2_AGENT_COUNT",
		"CLAUDE_CODE_PLAN_V2_EXPLORE_AGENT_COUNT", "CLAUDE_CODE_WORKFLOW_MAX_CONCURRENT_AGENTS",
		"CLAUDE_CODE_WORKFLOW_SUBAGENT_MODEL");

	/**
	 * Copies the parent's connection variables and subagent settings from the parent process into an isolated
	 * environment. A variable that the parent does not set stays unset.
	 *
	 * @param source      the parent process environment
	 * @param destination the mutable isolated process environment
	 */
	public static void copyProcessEnvironment(Map<String, String> source, Map<String, String> destination)
	{
		HarnessEnvironment.copyAllowed(source, destination, INHERITED_VARIABLES);
	}

	/**
	 * Copies the connection variables and subagent settings that a host Claude settings file sets in its {@code env}
	 * into an isolated process environment, except those that the environment already holds, so that a value from the
	 * parent process wins over the settings file's.
	 *
	 * @param json        the JSON mapper that reads the settings file
	 * @param source      the host settings file
	 * @param environment the mutable isolated process environment
	 * @throws IOException if the settings file cannot be read
	 */
	public static void copy(JsonMapper json, Path source, Map<String, String> environment) throws IOException
	{
		if (!Files.isRegularFile(source))
			return;
		JsonNode configured = json.readTree(BoundedInput.readUtf8(source, MAXIMUM_SETTINGS_BYTES,
			"Claude settings")).path("env");
		if (!configured.isObject())
			return;
		for (String variable : INHERITED_VARIABLES)
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
	 * The resulting file retains none of the parent's {@code sandbox} settings. It retains the parent's permission
	 * rules that deny a tool or ask before using it, and CAT-owned environment values. Its commands reach only
	 * Anthropic's API host. The instance may write only to {@code claudeHome} and {@code dependencyCaches}, not to
	 * the parent's writable paths, such as its Claude and CAT directories. It does not retain the parent's permission
	 * grants, such as its allow rules, added directories and default permission mode, so the isolated instance cannot
	 * use them to reach the parent's directories. It allows the {@code Skill} tool instead, which the isolated
	 * instance has no user to approve. It requires a sandbox and forbids commands outside it, whatever the parent's
	 * settings say, and lets the instance run its commands, which that sandbox confines, without approval.
	 * <p>
	 * Host settings must not control other isolated-instance behavior. It also denies the instance reading
	 * each of {@code deniedReads}: the sandbox denies its commands through {@code sandbox.filesystem.denyRead}, and a
	 * {@code Read} rule in {@code permissions.deny} denies its file tools, which the sandbox does not confine.
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
	 * @throws IOException if settings I/O fails
	 */
	public static void writeIsolatedSettings(HarnessRunnerScope scope, Path source, Path destination,
		Path claudeTemporaryDirectory, Path claudeHome, List<Path> dependencyCaches, Path isolationRoot,
		List<Path> deniedReads)
		throws IOException
	{
		writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory, claudeHome, dependencyCaches,
			isolationRoot, deniedReads, "");
	}

	/**
	 * Writes settings for an isolated Claude instance, as
	 * {@link #writeIsolatedSettings(HarnessRunnerScope, Path, Path, Path, Path, List, Path, List)} does,
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
	 * @throws NullPointerException if {@code instructionFilesMode} is null
	 * @throws IOException          if settings I/O fails
	 */
	public static void writeIsolatedSettings(HarnessRunnerScope scope, Path source, Path destination,
		Path claudeTemporaryDirectory, Path claudeHome, List<Path> dependencyCaches, Path isolationRoot,
		List<Path> deniedReads, String instructionFilesMode)
		throws IOException
	{
		writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory, claudeHome, dependencyCaches,
			isolationRoot, deniedReads, instructionFilesMode, null, null);
	}

	/**
	 * Writes settings for an isolated Claude instance, as
	 * {@link #writeIsolatedSettings(HarnessRunnerScope, Path, Path, Path, Path, List, Path, List, String)}
	 * does, except that the sandbox also lets the instance's commands write the run's requests directory when the
	 * session may request sessions. It also permits the literal launcher command with any arguments and reads of
	 * the run's responses directory and its descendants. When {@code project} is not null, the sandbox also lets the
	 * instance's commands write {@code .git/worktrees} in it, which this method creates, so that Git can register the
	 * linked worktrees that the instance adds; no other path under {@code .git} becomes writable.
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
	 * @param project                  the instance's Git repository, whose {@code .git} is a directory, or
	 *                                 {@code null} if the instance may register no linked worktree
	 * @throws NullPointerException if {@code instructionFilesMode} is null
	 * @throws IOException          if settings I/O fails
	 */
	public static void writeIsolatedSettings(HarnessRunnerScope scope, Path source, Path destination,
		Path claudeTemporaryDirectory, Path claudeHome, List<Path> dependencyCaches, Path isolationRoot,
		List<Path> deniedReads, String instructionFilesMode, SessionRequestAccess requestAccess, Path project)
		throws IOException
	{
		writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory, claudeHome, dependencyCaches,
			isolationRoot, deniedReads, instructionFilesMode, requestAccess, project, isolationRoot, null,
			ExtraPermissions.NONE, DEFAULT_MODEL_HOST);
	}

	/**
	 * Writes settings for an isolated Claude instance, as
	 * {@link #writeIsolatedSettings(HarnessRunnerScope, Path, Path, Path, Path, List, Path, List, String,
	 * SessionRequestAccess, Path)} does, except that the instance's sandbox policy is its needs bounded by
	 * {@code bound}, and its commands reach {@code modelHost} if {@code bound} lets them.
	 * <p>
	 * The instance needs to write {@code claudeHome}, which is always writable, the dependency caches, the requests
	 * directory and the worktree registrations; a cache or directory that {@code bound} does not let it write is not
	 * writable. Claude Code lets the instance's commands write the project and {@code claudeTemporaryDirectory}
	 * without a setting, so the session is refused unless {@code bound} lets them write those and
	 * {@code claudeHome}. {@code bound} always lets the instance write inside {@code runFolder}, so the session is
	 * refused only for the paths among them that lie outside it; the instance's commands still write only the paths
	 * that its policy lists, not the whole run folder. The sandbox settings carry exactly the resulting policy: its
	 * writable paths, the paths inside them that are not writable, the paths that are not readable (also as
	 * {@code Read} rules in {@code permissions.deny}, since the sandbox does not confine the file tools), the paths
	 * inside those that are readable nonetheless, and the network hosts. They require the sandbox, forbid every
	 * command outside it and exclude no command from it.
	 * <p>
	 * The file tools may read, and no setting lets them or the commands write, the run folder's {@code link-targets}
	 * directory and its descendants, which hold the read-only copies that the project's links to targets outside it
	 * lead to.
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
	 * @param project                  the instance's Git repository, whose {@code .git} is a directory, or
	 *                                 {@code null} if the instance may register no linked worktree
	 * @param runFolder                the instance's run directory, which {@code bound} always lets it write
	 * @param bound                    the policy that bounds the instance's, or {@code null} if nothing bounds it
	 * @param extraPermissions         the writes and hosts that the instance's commands need beyond what Claude Code
	 *                                 needs, which join its needs
	 * @param modelHost                the host of the instance's model provider
	 * @return the instance's sandbox policy
	 * @throws NullPointerException     if {@code instructionFilesMode}, {@code runFolder}, {@code extraPermissions}
	 *                                  or {@code modelHost} are null
	 * @throws IllegalArgumentException if {@code modelHost} is blank
	 * @throws SessionRefusedException  if {@code bound} does not let the instance's commands write {@code claudeHome},
	 *                                  {@code claudeTemporaryDirectory} or {@code project} outside {@code runFolder}, or
	 *                                  lacks a write or host that the instance needs
	 * @throws IOException              if settings I/O fails
	 */
	public static ChildPolicy writeIsolatedSettings(HarnessRunnerScope scope, Path source, Path destination,
		Path claudeTemporaryDirectory, Path claudeHome, List<Path> dependencyCaches, Path isolationRoot,
		List<Path> deniedReads, String instructionFilesMode, SessionRequestAccess requestAccess, Path project,
		Path runFolder, ChildPolicy bound, ExtraPermissions extraPermissions, String modelHost)
		throws IOException
	{
		return writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory, claudeHome, dependencyCaches,
			isolationRoot, deniedReads, instructionFilesMode, requestAccess, project, runFolder, bound, extraPermissions,
			modelHost, "");
	}

	/**
	 * Writes settings for an isolated Claude instance, as
	 * {@link #writeIsolatedSettings(HarnessRunnerScope, Path, Path, Path, Path, List, Path, List, String,
	 * SessionRequestAccess, Path, Path, ChildPolicy, ExtraPermissions, String, String, boolean, List)} does, for an
	 * instance that runs inside a sandbox and is offered every tool.
	 *
	 * @param scope                    services shared for the runner process lifetime
	 * @param source                   the host Claude settings file
	 * @param destination              the isolated Claude settings file
	 * @param claudeTemporaryDirectory the CAT-owned directory for Claude temporary files
	 * @param claudeHome               the CAT-owned home directory for the isolated Claude process
	 * @param dependencyCaches         the parent's shared dependency caches that the isolated instance may write
	 * @param isolationRoot            the directory above which the isolated instance loads no instruction file
	 * @param deniedReads              the existing files and directories that the isolated instance must not read
	 * @param instructionFilesMode     the choice of which instruction files load, or an empty string to keep the
	 *                                 parent's choice
	 * @param requestAccess            the session's access to requested sessions, or {@code null} if it may request
	 *                                 none
	 * @param project                  the instance's Git repository, or {@code null} if the instance may register no
	 *                                 linked worktree
	 * @param runFolder                the instance's run directory, which {@code bound} always lets it write
	 * @param bound                    the policy that bounds the instance's, or {@code null} if nothing bounds it
	 * @param extraPermissions         the writes and hosts that the instance's commands need beyond what Claude Code
	 *                                 needs
	 * @param modelHost                the host of the instance's model provider
	 * @param promptCacheTtl           the lifetime of the instance's prompt cache, such as {@code 5m}, or an empty
	 *                                 string to keep Claude Code's default
	 * @return the instance's sandbox policy
	 * @throws NullPointerException     if {@code instructionFilesMode}, {@code runFolder}, {@code extraPermissions},
	 *                                  {@code modelHost} or {@code promptCacheTtl} are null
	 * @throws IllegalArgumentException if {@code modelHost} is blank
	 * @throws SessionRefusedException  if {@code bound} does not let the instance's commands write what Claude Code
	 *                                  writes, or lacks a write or host that the instance needs
	 * @throws IOException              if settings I/O fails
	 */
	public static ChildPolicy writeIsolatedSettings(HarnessRunnerScope scope, Path source, Path destination,
		Path claudeTemporaryDirectory, Path claudeHome, List<Path> dependencyCaches, Path isolationRoot,
		List<Path> deniedReads, String instructionFilesMode, SessionRequestAccess requestAccess, Path project,
		Path runFolder, ChildPolicy bound, ExtraPermissions extraPermissions, String modelHost, String promptCacheTtl)
		throws IOException
	{
		return writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory, claudeHome, dependencyCaches,
			isolationRoot, deniedReads, instructionFilesMode, requestAccess, project, runFolder, bound, extraPermissions,
			modelHost, promptCacheTtl, true, List.of());
	}

	/**
	 * Writes settings for an isolated Claude instance, as
	 * {@link #writeIsolatedSettings(HarnessRunnerScope, Path, Path, Path, Path, List, Path, List, String,
	 * SessionRequestAccess, Path, Path, ChildPolicy, ExtraPermissions, String)} does, except that the settings also set
	 * the lifetime of the instance's prompt cache when {@code promptCacheTtl} is not empty. Claude Code's
	 * {@code promptCacheTtl} setting alone switches every cache write to that lifetime, so the settings set no
	 * environment variable for it.
	 * <p>
	 * When {@code sandboxed} is {@code false}, the instance runs without a sandbox, because the {@code tools} that its
	 * model is offered include no command and no tool that writes. The settings then have no {@code sandbox} section, do
	 * not allow the {@code Skill} and editing tools, commands, or requested sessions' responses that a sandboxed
	 * session needs, and do not require Claude Code to start a sandbox. Its file tools stay out of the paths that
	 * {@code deniedReads} names through the same {@code Read} rules in {@code permissions.deny} as a sandboxed session's,
	 * and every MCP tool is denied, because such a tool runs outside any sandbox. The returned policy and its read
	 * denials are the same either way. The session is refused when {@code tools} is empty, which offers every tool, or
	 * names a tool that {@link ClaudeSessionSandbox#READ_ONLY_TOOLS} does not list.
	 *
	 * @param scope                    services shared for the runner process lifetime
	 * @param source                   the host Claude settings file
	 * @param destination              the isolated Claude settings file
	 * @param claudeTemporaryDirectory the CAT-owned directory for Claude temporary files
	 * @param claudeHome               the CAT-owned home directory for the isolated Claude process
	 * @param dependencyCaches         the parent's shared dependency caches that the isolated instance may write
	 * @param isolationRoot            the directory above which the isolated instance loads no instruction file
	 * @param deniedReads              the existing files and directories that the isolated instance must not read
	 * @param instructionFilesMode     the choice of which instruction files load, or an empty string to keep the
	 *                                 parent's choice
	 * @param requestAccess            the session's access to requested sessions, or {@code null} if it may request
	 *                                 none
	 * @param project                  the instance's Git repository, or {@code null} if the instance may register no
	 *                                 linked worktree
	 * @param runFolder                the instance's run directory, which {@code bound} always lets it write
	 * @param bound                    the policy that bounds the instance's, or {@code null} if nothing bounds it
	 * @param extraPermissions         the writes and hosts that the instance's commands need beyond what Claude Code
	 *                                 needs
	 * @param modelHost                the host of the instance's model provider
	 * @param promptCacheTtl           the lifetime of the instance's prompt cache, such as {@code 5m}, or an empty
	 *                                 string to keep Claude Code's default
	 * @param sandboxed                {@code true} if Claude Code confines the instance's commands in a sandbox,
	 *                                 {@code false} if the instance runs without one
	 * @param tools                    the only tools that the instance's model is offered, or none if it is offered
	 *                                 every tool
	 * @return the instance's sandbox policy
	 * @throws NullPointerException     if {@code instructionFilesMode}, {@code runFolder}, {@code extraPermissions},
	 *                                  {@code modelHost}, {@code promptCacheTtl} or {@code tools} are null
	 * @throws IllegalArgumentException if {@code modelHost} is blank
	 * @throws SessionRefusedException  if {@code sandboxed} is {@code false} and {@code tools} is empty or names a tool
	 *                                  beyond {@link ClaudeSessionSandbox#READ_ONLY_TOOLS}; or if {@code bound} does not
	 *                                  let the instance's commands write what Claude Code writes, or lacks a write or
	 *                                  host that the instance needs; or if {@code sandboxed} is {@code true} and the
	 *                                  read denials are too many for Claude Code to sandbox the instance's commands
	 * @throws IOException              if settings I/O fails
	 */
	public static ChildPolicy writeIsolatedSettings(HarnessRunnerScope scope, Path source, Path destination,
		Path claudeTemporaryDirectory, Path claudeHome, List<Path> dependencyCaches, Path isolationRoot,
		List<Path> deniedReads, String instructionFilesMode, SessionRequestAccess requestAccess, Path project,
		Path runFolder, ChildPolicy bound, ExtraPermissions extraPermissions, String modelHost, String promptCacheTtl,
		boolean sandboxed, List<String> tools)
		throws IOException
	{
		requireThat(promptCacheTtl, "promptCacheTtl").isNotNull();
		requireThat(instructionFilesMode, "instructionFilesMode").isNotNull();
		requireThat(runFolder, "runFolder").isNotNull();
		requireThat(extraPermissions, "extraPermissions").isNotNull();
		requireThat(modelHost, "modelHost").isNotBlank();
		requireThat(tools, "tools").isNotNull();
		if (!sandboxed)
		{
			Optional<String> refusal = ClaudeSessionSandbox.unsandboxedRefusal(tools);
			if (refusal.isPresent())
				throw new SessionRefusedException(refusal.get());
		}
		Path worktreeRegistrations = null;
		if (project != null)
		{
			// Git registers each linked worktree below this directory; the sandbox grants only a path that exists
			worktreeRegistrations = project.toAbsolutePath().normalize().resolve(".git").resolve("worktrees");
		}
		List<Path> sessionWrites = new ArrayList<>(dependencyCaches);
		if (requestAccess != null)
			sessionWrites.add(requestAccess.requests());
		if (worktreeRegistrations != null)
			sessionWrites.add(worktreeRegistrations);
		List<Path> implicitWrites = new ArrayList<>();
		implicitWrites.add(claudeTemporaryDirectory);
		if (project != null)
			implicitWrites.add(project);
		ChildPolicy policy = childPolicy(bound, runFolder, claudeHome, sessionWrites, implicitWrites, deniedReads,
			extraPermissions, modelHost);

		JsonMapper mapper = scope.json();
		ObjectNode settings = mapper.createObjectNode();
		ObjectNode environment = settings.putObject("env");
		environment.put("DISABLE_AUTOUPDATER", "1");
		environment.put("CLAUDE_CODE_TMPDIR", claudeTemporaryDirectory.toString());
		JsonNode parentSettings = mapper.missingNode();
		if (Files.isRegularFile(source))
		{
			parentSettings = mapper.readTree(BoundedInput.readUtf8(source, MAXIMUM_SETTINGS_BYTES,
				"Claude settings"));
		}
		if (sandboxed)
			putSandbox(settings, policy, implicitWrites);
		if (!promptCacheTtl.isEmpty())
			settings.put("promptCacheTtl", promptCacheTtl);
		copyPermissionRestrictions(parentSettings.path("permissions"), settings);
		if (instructionFilesMode.isEmpty())
			copyInstructionFilesMode(parentSettings, settings);
		else
			putInstructionFilesMode(settings, instructionFilesMode);
		ArrayNode grants = permissions(settings).putArray("allow");
		if (sandboxed)
		{
			for (String grant : PERMISSION_GRANTS)
				grants.add(grant);
		}
		// The project's links to targets outside it lead to the copies in the run's link-targets directory, which the
		// file tools read through the links; no rule or sandbox path lets the session write there
		grants.add("Read(" + rulePattern(HarnessRunManager.linkTargets(runFolder)) + "/**)");
		// A session without a sandbox has no command to run and requests no session
		if (sandboxed && requestAccess != null)
		{
			grants.add("Bash(" + requestAccess.launcher() + ":*)");
			for (Path runner : requestAccess.runners())
				grants.add("Bash(" + runner + ":*)");
			grants.add("Read(" + rulePattern(requestAccess.responses()) + "/**)");
		}
		if (sandboxed)
			grants.add(SANDBOXED_COMMAND_GRANT);
		denyReadRules(settings, policy.readDenials(), policy.writeRoots());
		if (!sandboxed)
			denyMcpTools(settings);
		excludeInstructionsAbove(settings, isolationRoot);
		// The limit is on the command line that starts a sandboxed command, and a session without a sandbox has none
		if (sandboxed)
			requireApplicableDenials(settings);

		Files.createDirectories(claudeTemporaryDirectory);
		Files.createDirectories(claudeHome);
		if (worktreeRegistrations != null)
			Files.createDirectories(worktreeRegistrations);
		Files.createDirectories(destination.getParent());
		Files.writeString(destination, mapper.writeValueAsString(settings));
		return policy;
	}

	/**
	 * Refuses settings whose denied paths are too many for Claude Code to sandbox the session's commands.
	 *
	 * @param settings the isolated settings
	 * @throws SessionRefusedException if the paths of the read denials and permission rules total more than
	 *                                 {@link #MAXIMUM_DENIED_PATH_BYTES}
	 */
	private static void requireApplicableDenials(ObjectNode settings) throws SessionRefusedException
	{
		long bytes = 0;
		List<JsonNode> lists = new ArrayList<>();
		lists.add(settings.path("sandbox").path("filesystem").path("denyRead"));
		for (String list : PERMISSION_RESTRICTIONS)
			lists.add(settings.path("permissions").path(list));
		for (JsonNode entries : lists)
		{
			for (JsonNode entry : entries)
				bytes += entry.asString().getBytes(StandardCharsets.UTF_8).length;
		}
		if (bytes > MAXIMUM_DENIED_PATH_BYTES)
		{
			throw new SessionRefusedException("The session's read denials total " + bytes + " bytes of paths, more " +
				"than the " + MAXIMUM_DENIED_PATH_BYTES + " that Claude Code can sandbox its commands with, so CAT " +
				"does not start a session whose commands would all wait for approval");
		}
	}

	/**
	 * Returns an isolated instance's sandbox policy: its needs, bounded by the policy that bounds it.
	 *
	 * @param bound          the policy that bounds the instance's, or {@code null} if nothing bounds it
	 * @param runFolder      the instance's run directory, which {@code bound} always lets it write: only the writes
	 *                       outside it need {@code bound}'s permission, and the instance's commands write only the
	 *                       paths of its policy inside it
	 * @param claudeHome     the instance's home directory, which it always writes
	 * @param sessionWrites  the other paths that the instance's commands may write if {@code bound} lets them
	 * @param implicitWrites the paths that Claude Code lets the commands write without a setting
	 * @param deniedReads    the paths that the instance must not read
	 * @param extra          the writes and hosts that the instance needs beyond what Claude Code needs
	 * @param modelHost      the host of the instance's model provider
	 * @return the policy
	 * @throws SessionRefusedException if {@code bound} does not let the commands write {@code claudeHome} or one of
	 *                                 {@code implicitWrites} outside {@code runFolder}
	 * @throws IOException             if a symbolic link in a path cannot be read
	 */
	private static ChildPolicy childPolicy(ChildPolicy bound, Path runFolder, Path claudeHome,
		List<Path> sessionWrites, List<Path> implicitWrites, List<Path> deniedReads, ExtraPermissions extra,
		String modelHost)
		throws IOException
	{
		ParentPolicy parent = ParentPolicy.UNSANDBOXED;
		if (bound != null)
		{
			Path run = runFolder.toAbsolutePath().normalize();
			List<Path> required = new ArrayList<>();
			required.add(claudeHome);
			required.addAll(implicitWrites);
			for (Path path : required)
			{
				Path absolute = path.toAbsolutePath().normalize();
				// The run folder is the instance's own, so the bound does not decide whether it may write there
				if (isInsideAny(RealPaths.resolve(absolute), List.of(run)))
					continue;
				if (!writes(bound, absolute))
				{
					throw new SessionRefusedException("The session's sandbox policy does not let its " +
						"commands write " + absolute + ", which Claude Code writes, so CAT does not start the session");
				}
			}
			ParentPolicy boundPolicy = bound.asParentPolicy();
			List<Path> writeRoots = new ArrayList<>(boundPolicy.writeRoots());
			writeRoots.add(run);
			parent = new ParentPolicy(true, writeRoots, boundPolicy.writeDenials(), boundPolicy.readDenials(),
				boundPolicy.readReallowances(), boundPolicy.hosts(), boundPolicy.allowLocalBinding(),
				boundPolicy.protectedSources());
		}
		List<Path> extraWrites = new ArrayList<>();
		for (Path path : sessionWrites)
			extraWrites.add(path.toAbsolutePath());
		for (Path path : implicitWrites)
			extraWrites.add(path.toAbsolutePath());
		List<Path> reads = new ArrayList<>();
		for (Path path : deniedReads)
			reads.add(path.toAbsolutePath());
		return ChildPolicy.intersect(parent, new ChildNeeds(claudeHome.toAbsolutePath(), extraWrites, reads,
			Set.of(modelHost.strip().toLowerCase(Locale.ROOT))).with(extra));
	}

	/**
	 * Adds the sandbox settings of a session whose commands the sandbox confines: it requires the sandbox, forbids
	 * commands outside it, and confines the commands to a policy.
	 *
	 * @param settings       the isolated settings
	 * @param policy         the session's sandbox policy
	 * @param implicitWrites the paths that Claude Code lets the commands write without a setting, which the settings
	 *                       therefore do not list
	 * @throws IOException if a symbolic link in a path cannot be read
	 */
	private static void putSandbox(ObjectNode settings, ChildPolicy policy, List<Path> implicitWrites)
		throws IOException
	{
		assert settings != null;
		assert policy != null;
		ObjectNode sandbox = settings.putObject("sandbox");
		sandbox.put("enabled", true);
		sandbox.put("failIfUnavailable", true);
		sandbox.put("allowUnsandboxedCommands", false);
		ObjectNode filesystem = sandbox.putObject("filesystem");
		Set<Path> implicit = new HashSet<>();
		for (Path path : implicitWrites)
			implicit.add(RealPaths.resolve(path.toAbsolutePath()));
		ArrayNode allowWrite = filesystem.putArray("allowWrite");
		for (Path root : policy.writeRoots())
		{
			if (!implicit.contains(root))
				allowWrite.add(root.toString());
		}
		putPaths(filesystem, "denyWrite", policy.writeDenials());
		putPaths(filesystem, "denyRead", policy.readDenials());
		putPaths(filesystem, "allowRead", policy.readReallowances());
		ObjectNode network = sandbox.putObject("network");
		ArrayNode domains = network.putArray("allowedDomains");
		for (String host : new TreeSet<>(policy.hosts()))
			domains.add(host);
		if (policy.allowLocalBinding())
			network.put("allowLocalBinding", true);
	}

	/**
	 * Adds a list of paths to settings, unless the list is empty.
	 *
	 * @param parent the settings object
	 * @param name   the name of the list
	 * @param paths  the paths
	 */
	private static void putPaths(ObjectNode parent, String name, List<Path> paths)
	{
		if (paths.isEmpty())
			return;
		ArrayNode array = parent.putArray(name);
		for (Path path : paths)
			array.add(path.toString());
	}

	/**
	 * Returns the host of the model provider that an isolated instance connects to: the host of
	 * {@code ANTHROPIC_BASE_URL} when the environment sets it, and Anthropic's API host otherwise.
	 *
	 * @param environment the instance's environment
	 * @return the host, in lower case
	 * @throws NullPointerException     if {@code environment} is null
	 * @throws IllegalArgumentException if {@code ANTHROPIC_BASE_URL} is not a URL with a host
	 */
	public static String modelHost(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		String baseUrl = environment.get(BASE_URL_VARIABLE);
		if (baseUrl == null || baseUrl.isBlank())
			return DEFAULT_MODEL_HOST;
		String host = URI.create(baseUrl.strip()).getHost();
		if (host == null)
			throw new IllegalArgumentException(BASE_URL_VARIABLE + " names no host: " + baseUrl);
		return host.toLowerCase(Locale.ROOT);
	}

	/**
	 * Returns the managed settings files that Claude Code may load on a platform and that exist. On Linux these
	 * include the Windows managed settings that WSL reaches.
	 *
	 * @param platform the platform
	 * @return the files of each folder, in the order that {@link #managedSettings(Path)} describes
	 * @throws NullPointerException if {@code platform} is null
	 * @throws IOException          if a managed settings folder cannot be listed
	 */
	public static List<Path> managedSettings(Platform platform) throws IOException
	{
		requireThat(platform, "platform").isNotNull();
		if (platform.isLinux())
		{
			List<Path> files = new ArrayList<>(managedSettings(LINUX_MANAGED_SETTINGS));
			files.addAll(managedSettings(WSL_WINDOWS_MANAGED_SETTINGS));
			return files;
		}
		if (platform.isWindows())
			return managedSettings(WINDOWS_MANAGED_SETTINGS);
		return managedSettings(MACOS_MANAGED_SETTINGS);
	}

	/**
	 * Returns the managed settings files that Claude Code loads from a folder and that exist: its
	 * {@code managed-settings.json}, followed by the JSON files of its {@code managed-settings.d} folder in name
	 * order.
	 *
	 * @param directory the folder
	 * @return the files
	 * @throws NullPointerException if {@code directory} is null
	 * @throws IOException          if {@code managed-settings.d} cannot be listed
	 */
	public static List<Path> managedSettings(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		List<Path> files = new ArrayList<>();
		Path main = directory.resolve("managed-settings.json");
		if (Files.isRegularFile(main))
			files.add(main);
		Path drops = directory.resolve("managed-settings.d");
		if (!Files.isDirectory(drops))
			return files;
		List<Path> dropFiles = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(drops, "*.json"))
		{
			for (Path entry : entries)
			{
				if (Files.isRegularFile(entry))
					dropFiles.add(entry);
			}
		}
		dropFiles.sort(Comparator.comparing(entry -> entry.getFileName().toString()));
		files.addAll(dropFiles);
		return files;
	}

	/**
	 * Looks for a setting that would give an isolated instance's commands an access that its sandbox policy does not
	 * give, in a settings file that Claude Code loads beside the isolated settings, such as a managed settings file
	 * or the project's settings. Claude Code combines the lists of every settings file and lets a later file turn a
	 * safeguard off, so one such setting widens the session.
	 * <p>
	 * A widening setting turns the sandbox off or lets the session start without it, lets commands run outside it,
	 * excludes commands from it or weakens it; lets commands write a path outside the policy's writable paths, or
	 * read a path that the policy denies; lets commands reach a host outside the policy's, listen on local addresses
	 * when the policy does not, use local sockets, or use a proxy of its own; adds a directory that the policy does
	 * not let commands write; or lets the file tools edit outside the policy's writable paths.
	 *
	 * @param json   the JSON mapper
	 * @param files  the settings files; a path that is not a regular file is skipped
	 * @param policy the instance's sandbox policy
	 * @param home   the instance's home directory, which {@code ~} in a path names
	 * @return why the first widening setting is refused, naming its file and setting, or an empty value if no file
	 *   widens the session
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a settings file cannot be read
	 */
	public static Optional<String> findWidening(JsonMapper json, List<Path> files, ChildPolicy policy, Path home)
		throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(files, "files").isNotNull();
		requireThat(policy, "policy").isNotNull();
		requireThat(home, "home").isNotNull();
		for (Path file : files)
		{
			if (!Files.isRegularFile(file))
				continue;
			JsonNode settings;
			try
			{
				settings = json.readTree(BoundedInput.readUtf8(file, MAXIMUM_SETTINGS_BYTES, "Claude settings"));
			}
			catch (JacksonException _)
			{
				return Optional.of(refusal(file, "its content", "cannot be parsed, so CAT cannot tell what it allows"));
			}
			if (!settings.isObject())
				return Optional.of(refusal(file, "its content", "is not a JSON object, so CAT cannot tell what it allows"));
			Optional<String> widening = new WideningSettings(file, settings, policy, home.toAbsolutePath()).find();
			if (widening.isPresent())
				return widening;
		}
		return Optional.empty();
	}

	/**
	 * Returns why a setting is refused.
	 *
	 * @param file    the settings file
	 * @param setting the setting
	 * @param why     what the setting does, as a clause
	 * @return the reason, as sentences without the last one's final period
	 */
	private static String refusal(Path file, String setting, String why)
	{
		return "Claude Code would load the settings file " + file + ", whose " + setting + " " + why +
			", beyond the session's sandbox policy, so CAT does not start the session. Remove the setting from that " +
			"file";
	}

	/**
	 * Indicates whether a policy lets commands write a path.
	 *
	 * @param policy the policy
	 * @param path   an absolute path
	 * @return {@code true} if {@code path} lies inside one of the policy's writable paths and inside none of the paths
	 *   inside them that are not writable
	 * @throws IOException if a symbolic link in a path cannot be read
	 */
	private static boolean writes(ChildPolicy policy, Path path) throws IOException
	{
		Path resolved = RealPaths.resolve(path);
		return isInsideAny(resolved, policy.writeRoots()) && !isInsideAny(resolved, policy.writeDenials());
	}

	/**
	 * Indicates whether a resolved path lies inside, or is, one of some paths.
	 *
	 * @param resolved a resolved path
	 * @param paths    absolute paths
	 * @return {@code true} if {@code resolved} lies inside one of {@code paths}
	 * @throws IOException if a symbolic link in a path cannot be read
	 */
	private static boolean isInsideAny(Path resolved, List<Path> paths) throws IOException
	{
		return RealPaths.isInsideAny(resolved, RealPaths.resolveAll(paths));
	}

	/**
	 * The settings of one file that Claude Code loads beside the isolated settings, checked against a policy.
	 */
	private static final class WideningSettings
	{
		/**
		 * The tools whose allow rules let the file tools change files.
		 */
		private static final Set<String> EDITING_TOOLS = Set.of("Edit", "Write", "MultiEdit", "NotebookEdit");
		private final Path file;
		private final JsonNode settings;
		private final ChildPolicy policy;
		private final Path home;

		/**
		 * Creates the settings.
		 *
		 * @param file     the settings file
		 * @param settings its settings
		 * @param policy   the session's sandbox policy
		 * @param home     the session's home directory
		 */
		WideningSettings(Path file, JsonNode settings, ChildPolicy policy, Path home)
		{
			this.file = file;
			this.settings = settings;
			this.policy = policy;
			this.home = home;
		}

		/**
		 * Looks for the first setting that widens the session.
		 *
		 * @return why it is refused, or an empty value if none does
		 * @throws IOException if a symbolic link in a path cannot be read
		 */
		Optional<String> find() throws IOException
		{
			JsonNode sandbox = settings.path("sandbox");
			if (isFalse(sandbox.path("enabled")))
				return refuse("sandbox.enabled", "turns the sandbox off");
			if (isFalse(sandbox.path("failIfUnavailable")))
				return refuse("sandbox.failIfUnavailable", "lets the session start without its sandbox");
			if (sandbox.path("allowUnsandboxedCommands").asBoolean(false))
				return refuse("sandbox.allowUnsandboxedCommands", "lets commands run outside the sandbox");
			if (!sandbox.path("excludedCommands").isEmpty())
				return refuse("sandbox.excludedCommands", "runs commands outside the sandbox");
			if (sandbox.path("enableWeakerNestedSandbox").asBoolean(false))
				return refuse("sandbox.enableWeakerNestedSandbox", "weakens the sandbox");
			JsonNode filesystem = sandbox.path("filesystem");
			for (JsonNode entry : filesystem.path("allowWrite"))
			{
				if (!writesAll(entry.asString(), false))
					return refuse("sandbox.filesystem.allowWrite", "lets commands write " + entry.asString());
			}
			for (JsonNode entry : filesystem.path("allowRead"))
			{
				if (reopensDenial(entry.asString()))
					return refuse("sandbox.filesystem.allowRead", "lets commands read " + entry.asString());
			}
			Optional<String> network = findNetworkWidening(sandbox.path("network"));
			if (network.isPresent())
				return network;
			JsonNode permissions = settings.path("permissions");
			for (JsonNode entry : permissions.path("additionalDirectories"))
			{
				if (!writesAll(entry.asString(), false))
				{
					return refuse("permissions.additionalDirectories", "lets the session work in " +
						entry.asString());
				}
			}
			for (JsonNode entry : permissions.path("allow"))
			{
				if (editsOutsidePolicy(entry.asString()))
					return refuse("permissions.allow", "lets the file tools change files through " + entry.asString());
			}
			return Optional.empty();
		}

		/**
		 * Looks for the first network setting that widens the session.
		 *
		 * @param network the {@code sandbox.network} settings
		 * @return why it is refused, or an empty value if none does
		 */
		private Optional<String> findNetworkWidening(JsonNode network)
		{
			HostAccess hosts = policy.asParentPolicy().hosts();
			for (JsonNode entry : network.path("allowedDomains"))
			{
				String domain = entry.asString();
				if (domain.isBlank() || !hosts.covers(domain))
					return refuse("sandbox.network.allowedDomains", "lets commands reach " + domain);
			}
			if (network.path("allowLocalBinding").asBoolean(false) && !policy.allowLocalBinding())
				return refuse("sandbox.network.allowLocalBinding", "lets commands listen on local addresses");
			if (!network.path("allowUnixSockets").isEmpty())
				return refuse("sandbox.network.allowUnixSockets", "lets commands use local sockets");
			if (network.path("allowAllUnixSockets").asBoolean(false))
				return refuse("sandbox.network.allowAllUnixSockets", "lets commands use every local socket");
			for (String port : List.of("httpProxyPort", "socksProxyPort"))
			{
				if (network.has(port))
				{
					return refuse("sandbox.network." + port, "routes commands through a proxy whose hosts CAT " +
						"cannot check");
				}
			}
			return Optional.empty();
		}

		/**
		 * Returns why a setting is refused.
		 *
		 * @param setting the setting
		 * @param why     what the setting does, as a clause
		 * @return the reason
		 */
		private Optional<String> refuse(String setting, String why)
		{
			return Optional.of(refusal(file, setting, why));
		}

		/**
		 * Indicates whether a setting is {@code false}.
		 *
		 * @param value the setting, or a missing node
		 * @return {@code true} if the setting is the boolean {@code false}
		 */
		private static boolean isFalse(JsonNode value)
		{
			return value.isBoolean() && !value.booleanValue();
		}

		/**
		 * Indicates whether the policy lets commands write every path that a path setting may name.
		 *
		 * @param entry      the path setting
		 * @param ruleSyntax {@code true} if {@code entry} is the path of a permission rule
		 * @return {@code true} if the policy lets commands write each path that {@code entry} may name
		 * @throws IOException if a symbolic link in a path cannot be read
		 */
		private boolean writesAll(String entry, boolean ruleSyntax) throws IOException
		{
			for (Path candidate : candidates(entry, ruleSyntax))
			{
				if (!writes(policy, candidate))
					return false;
			}
			return true;
		}

		/**
		 * Indicates whether a path setting re-opens a path that the policy does not let commands read.
		 *
		 * @param entry the path setting
		 * @return {@code true} if a path that {@code entry} may name lies inside or around a read denial, outside the
		 *   policy's re-allowances
		 * @throws IOException if a symbolic link in a path cannot be read
		 */
		private boolean reopensDenial(String entry) throws IOException
		{
			for (Path candidate : candidates(entry, false))
			{
				Path resolved = RealPaths.resolve(candidate);
				if (isInsideAny(resolved, policy.readReallowances()))
					continue;
				for (Path denial : policy.readDenials())
				{
					Path resolvedDenial = RealPaths.resolve(denial);
					if (resolvedDenial.startsWith(resolved) || resolved.startsWith(resolvedDenial))
						return true;
				}
			}
			return false;
		}

		/**
		 * Indicates whether an allow rule lets the file tools change a file outside the policy's writable paths.
		 *
		 * @param rule the rule, such as {@code Edit(//p/**)}
		 * @return {@code true} if the rule is for a tool that changes files and may match a path that the policy does
		 *   not let commands write
		 * @throws IOException if a symbolic link in a path cannot be read
		 */
		private boolean editsOutsidePolicy(String rule) throws IOException
		{
			String tool = rule;
			String specifier = "";
			int open = rule.indexOf('(');
			if (open >= 0 && rule.endsWith(")"))
			{
				tool = rule.substring(0, open);
				specifier = rule.substring(open + 1, rule.length() - 1);
			}
			if (!EDITING_TOOLS.contains(tool.strip()))
				return false;
			return specifier.isBlank() || !writesAll(specifier, true);
		}

		/**
		 * Returns the absolute paths that a path setting may name. Claude Code reads {@code //} as the start of an
		 * absolute path and {@code ~/} as the home directory. A sandbox path that starts with one {@code /} is
		 * absolute, and a permission rule's is relative to the settings file's folder. Any other path may be relative
		 * to that folder or to the project that holds it, so each reading counts. The part from the first glob
		 * character on is dropped, so that the folder that holds every match remains.
		 * <p>
		 * On Windows, a path that starts at a drive, such as {@code C:\Users}, or at a network share is absolute, and a
		 * permission rule names a drive as Claude Code does, as {@code //c/Users} for {@code C:\Users}. Names are
		 * separated by {@code /}, so a backslash is not glob syntax there.
		 *
		 * @param entry      the path setting
		 * @param ruleSyntax {@code true} if {@code entry} is the path of a permission rule
		 * @return the paths
		 */
		private List<Path> candidates(String entry, boolean ruleSyntax)
		{
			Path folder = file.toAbsolutePath().getParent();
			boolean windows = folder.getFileSystem().getSeparator().equals("\\");
			String path = entry.strip();
			boolean networkShare = windows && path.startsWith("\\\\");
			if (windows)
				path = path.replace('\\', '/');
			for (int index = 0; index < path.length(); ++index)
			{
				if (GLOB_SYNTAX.indexOf(path.charAt(index)) >= 0)
				{
					path = path.substring(0, index);
					break;
				}
			}
			List<Path> result = new ArrayList<>();
			if (networkShare || windows && startsAtDrive(path))
				result.add(Path.of(path));
			else if (path.startsWith("//"))
			{
				String absolute = path.substring(1);
				if (windows)
					absolute = ruleDrive(absolute);
				result.add(Path.of(absolute));
			}
			else if (path.equals("~") || path.startsWith("~/"))
				result.add(home.resolve(path.substring(1).replaceFirst("^/+", "")));
			else if (path.startsWith("/") && ruleSyntax)
				result.add(folder.resolve(path.substring(1)));
			else if (path.startsWith("/"))
				result.add(Path.of(path));
			else
			{
				result.add(folder.resolve(path));
				if (folder.getParent() != null)
					result.add(folder.getParent().resolve(path));
			}
			List<Path> normalized = new ArrayList<>();
			for (Path candidate : result)
				normalized.add(candidate.toAbsolutePath().normalize());
			return normalized;
		}

		/**
		 * Indicates whether a path starts at a Windows drive.
		 *
		 * @param path a path that separates names with {@code /}
		 * @return {@code true} if {@code path} starts with a drive letter, a colon and {@code /}
		 */
		private static boolean startsAtDrive(String path)
		{
			return path.length() >= 3 && isDriveLetter(path.charAt(0)) && path.charAt(1) == ':' &&
				path.charAt(2) == '/';
		}

		/**
		 * Returns the Windows path that a permission rule names with the form of a POSIX path, such as
		 * {@code C:/Users} for {@code /c/Users}.
		 *
		 * @param path a path that starts with {@code /}
		 * @return the path with its drive, or {@code path} if it does not start with a drive letter's folder
		 */
		private static String ruleDrive(String path)
		{
			if (path.length() < 2 || !isDriveLetter(path.charAt(1)) || path.length() > 2 && path.charAt(2) != '/')
				return path;
			String rest = "/";
			if (path.length() > 2)
				rest = path.substring(2);
			return Character.toUpperCase(path.charAt(1)) + ":" + rest;
		}

		/**
		 * Indicates whether a character is the name of a Windows drive.
		 *
		 * @param character the character
		 * @return {@code true} if {@code character} is an ASCII letter
		 */
		private static boolean isDriveLetter(char character)
		{
			return character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z';
		}
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
	 * Denies the isolated instance's file tools reading files and directories, in addition to any rule that the
	 * parent's settings deny, with each rule naming its path as {@link #rulePattern(Path)} gives it. Does nothing if
	 * {@code deniedReads} is empty.
	 * <p>
	 * A directory that holds one of {@code writeRoots}, such as the project that holds the run folder of a session that
	 * runs in place, is denied as every file and directory in it except the write roots and the directories that lead
	 * to them. A Read rule that covered a write root would stop the instance's Write tool there, since that tool also
	 * refuses a path that a Read rule denies. A file or directory that appears in such a directory after this method
	 * returns is not denied.
	 *
	 * @param settings    the isolated settings
	 * @param deniedReads the existing files and directories to deny
	 * @param writeRoots  the directories that the instance's commands may write
	 * @throws IOException if a directory that holds a write root cannot be listed
	 */
	private static void denyReadRules(ObjectNode settings, List<Path> deniedReads, List<Path> writeRoots)
		throws IOException
	{
		assert settings != null;
		assert deniedReads != null;
		assert writeRoots != null;
		if (deniedReads.isEmpty())
			return;
		Set<Path> rulePaths = new LinkedHashSet<>();
		for (Path deniedRead : deniedReads)
		{
			List<Path> inside = new ArrayList<>();
			for (Path writeRoot : writeRoots)
			{
				if (writeRoot.startsWith(deniedRead) && !writeRoot.equals(deniedRead))
					inside.add(writeRoot);
			}
			if (inside.isEmpty())
				rulePaths.add(deniedRead);
			else
				rulePaths.addAll(HarnessRunManager.deniedReads(deniedRead, inside));
		}
		ArrayNode permissionDenials = array(permissions(settings), "deny");
		for (Path deniedRead : rulePaths)
		{
			// A rule path that starts with "//" is absolute; "**" also matches everything below a directory.
			String descendants;
			if (Files.isDirectory(deniedRead))
				descendants = "/**";
			else
				descendants = "";
			permissionDenials.add("Read(" + rulePattern(deniedRead) + descendants + ")");
		}
	}

	/**
	 * Denies the isolated instance every tool that an MCP server provides, unless the parent's rules that deny a tool
	 * already do.
	 *
	 * @param settings the isolated settings
	 */
	private static void denyMcpTools(ObjectNode settings)
	{
		assert settings != null;
		ArrayNode permissionDenials = array(permissions(settings), "deny");
		for (JsonNode denial : permissionDenials)
		{
			if (denial.asString().equals(MCP_TOOLS_DENIAL))
				return;
		}
		permissionDenials.add(MCP_TOOLS_DENIAL);
	}

	/**
	 * Returns the absolute path that a permission rule names, as a gitignore pattern that matches that path alone: the
	 * path as {@link #rulePath(Path)} gives it, behind the extra {@code /} that marks it absolute, with each character
	 * that a gitignore pattern treats as syntax escaped.
	 *
	 * @param path an absolute path
	 * @return the pattern, which starts with {@code //}, or with {@code /} followed by a drive on Windows
	 * @throws IllegalArgumentException if {@code path} is relative
	 */
	private static String rulePattern(Path path)
	{
		String names = rulePath(path);
		StringBuilder pattern = new StringBuilder(names.length() + 1).append('/');
		for (char character : names.toCharArray())
		{
			if (RULE_PATH_SYNTAX.indexOf(character) >= 0)
				pattern.append('\\');
			pattern.append(character);
		}
		return pattern.toString();
	}

	/**
	 * Excludes every instruction file that Claude Code would load from a directory above {@code isolationRoot}.
	 *
	 * @param settings      the isolated settings
	 * @param isolationRoot the directory above which no instruction file loads
	 */
	private static void excludeInstructionsAbove(ObjectNode settings, Path isolationRoot)
	{
		assert settings != null;
		assert isolationRoot != null;
		ArrayNode excludes = settings.putArray("claudeMdExcludes");
		for (Path directory = isolationRoot.toAbsolutePath().getParent(); directory != null;
			directory = directory.getParent())
		{
			String prefix = globPrefix(directory);
			for (String instruction : ANCESTOR_INSTRUCTIONS)
				excludes.add(prefix + instruction);
		}
	}

	/**
	 * Returns a directory as the start of a glob pattern that matches the files below it.
	 *
	 * @param directory an absolute directory
	 * @return the directory with its glob syntax escaped, ending with {@code /}
	 */
	private static String globPrefix(Path directory)
	{
		assert directory != null;
		String text = directory.toString();
		StringBuilder prefix = new StringBuilder(text.length() + 1);
		for (char character : text.toCharArray())
		{
			if (GLOB_SYNTAX.indexOf(character) >= 0)
				prefix.append('\\');
			prefix.append(character);
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
