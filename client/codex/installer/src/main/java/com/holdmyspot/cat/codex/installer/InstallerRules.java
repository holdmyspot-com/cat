package com.holdmyspot.cat.codex.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.EntryGroup;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes and recognizes the CAT-owned Codex execution-policy file that lets an agent run the installer launcher of the
 * active version folder and the request server beside it.
 * <p>
 * Each rule is a {@code prefix_rule}. Codex applies a prefix rule to every command that starts with its pattern, so
 * the installer validates its complete argument list itself. Every rule that allows a command names this harness
 * after {@code --harness}, or names {@code status}, so installing CAT into another harness always needs the user.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class InstallerRules
{
	/**
	 * The decision for the uninstall rule: Codex asks the user before an agent uninstalls CAT.
	 */
	private static final String UNINSTALL_DECISION = "prompt";
	/**
	 * The decision for operations that an agent may run without asking.
	 */
	private static final String ALLOW = "allow";
	/**
	 * The first line of the file, which tells the user who owns it.
	 */
	private static final String HEADER =
		"# Written by CAT's installer, which replaces or deletes this file. Do not edit it.";
	/**
	 * The first line of the file that CAT wrote, under another name, before its installer was called the installer.
	 */
	private static final String UPDATER_HEADER =
		"# Written by CAT's updater, which replaces or deletes this file. Do not edit it.";
	/**
	 * The fixed tokens that start every Windows launcher command, before the launcher script's path.
	 */
	private static final List<String> WINDOWS_PREFIX = List.of("powershell.exe", "-NoProfile", "-NonInteractive",
		"-ExecutionPolicy", "Bypass", "-File");
	/**
	 * The function that each rule calls.
	 */
	private static final String RULE_FUNCTION = "prefix_rule";
	/**
	 * The file name of the request server beside the installer launcher, which Codex runs outside its sandbox.
	 */
	private static final String REQUEST_SERVER = "request-server";
	/**
	 * The file names of the runner launchers beside the installer launcher, which earlier installers let Codex run
	 * outside its sandbox.
	 */
	private static final List<String> RUNNER_LAUNCHERS = List.of("claude-runner", "codex-runner");
	/**
	 * Quotes rule tokens as string literals.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/**
	 * Prevents instantiation.
	 */
	private InstallerRules()
	{
	}

	/**
	 * Returns the file content that permits the agent operations on one installer launcher and, except on Windows,
	 * where no session starts through a request server, lets the request server beside it run without asking and
	 * outside the sandbox, so that it can start the sessions that the session's sandboxed commands request inside
	 * Codex's own sandbox, which a sandboxed command cannot do. Codex matches each path only as spelled.
	 *
	 * @param launcher the absolute, normalized path of the installer launcher in the active version folder
	 * @param windows  {@code true} to name the launcher script through PowerShell, as Windows runs it
	 * @return the complete file content, ending with a line feed
	 * @throws NullPointerException     if {@code launcher} is null
	 * @throws IllegalArgumentException if {@code launcher} is relative or not normalized
	 */
	public static String render(Path launcher, boolean windows)
	{
		return render(HEADER, launcher, windows, false, siblings(windows));
	}

	/**
	 * Returns the launchers beside the installer launcher that {@link #render} lets Codex run outside its sandbox.
	 *
	 * @param windows {@code true} if the rules name the launcher script through PowerShell
	 * @return the request server, or none on Windows
	 */
	private static List<String> siblings(boolean windows)
	{
		if (windows)
			return List.of();
		return List.of(REQUEST_SERVER);
	}

	/**
	 * Groups the rules that {@link #render} writes for a launcher by what they let Codex do. Codex runs an operation
	 * or the request server that a rule allows without asking and outside its sandbox, and runs the operation of the rule
	 * that keeps its prompt outside its sandbox once the user agrees.
	 *
	 * @param content  the content of the rules file, as {@link #render} wrote it
	 * @param file     the rules file, relative to the Codex home
	 * @param launcher the absolute, normalized path of the installer launcher that the rules name
	 * @param windows  {@code true} if the rules name the launcher script through PowerShell
	 * @return the group of the rules that Codex runs without asking, and the group of the rules that Codex runs outside
	 * 	its sandbox; a rule that names another launcher or operation is in the entries of the second group only
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code file} is absolute, or {@code launcher} is relative or not normalized
	 */
	static List<EntryGroup> describe(String content, Path file, Path launcher, boolean windows)
	{
		requireThat(content, "content").isNotNull();
		requireThat(launcher, "launcher").isAbsolute().isEqualTo(launcher.normalize());
		List<String> command = new ArrayList<>();
		if (windows)
			command.addAll(WINDOWS_PREFIX);
		command.add(launcher.toString());
		Map<String, String> allowed = new HashMap<>();
		allowed.put(ruleLine(command, List.of("install", "--harness", CodexActivation.HARNESS), ALLOW),
			"installer install");
		allowed.put(ruleLine(command, List.of("status"), ALLOW), "installer status");
		allowed.put(ruleLine(List.of(launcher.resolveSibling(REQUEST_SERVER).toString()), List.of(), ALLOW),
			REQUEST_SERVER);
		Map<String, String> prompted = Map.of(
			ruleLine(command, List.of("uninstall", "--harness", CodexActivation.HARNESS), UNINSTALL_DECISION),
			"installer uninstall");

		List<String> allowedOperations = new ArrayList<>();
		List<String> allowedRules = new ArrayList<>();
		List<String> outsideOperations = new ArrayList<>();
		List<String> rules = new ArrayList<>();
		for (String line : content.lines().toList())
		{
			if (!line.startsWith(RULE_FUNCTION))
				continue;
			rules.add(line);
			String operation = allowed.get(line);
			if (operation != null)
			{
				allowedOperations.add(operation);
				allowedRules.add(line);
			}
			else
				operation = prompted.get(line);
			if (operation != null)
				outsideOperations.add(operation);
		}
		return List.of(new EntryGroup(EntryEffect.RUNS_WITHOUT_ASKING, file, "", allowedOperations, allowedRules),
			new EntryGroup(EntryEffect.RUNS_OUTSIDE_SANDBOX, file, "", outsideOperations, rules));
	}

	/**
	 * Returns one rule as {@link #render} writes it.
	 *
	 * @param command   the tokens that run the launcher
	 * @param operation the tokens of the operation that the rule permits
	 * @param decision  the rule's decision
	 * @return the rule's line, without its line feed
	 */
	private static String ruleLine(List<String> command, List<String> operation, String decision)
	{
		StringBuilder line = new StringBuilder();
		rule(line, command, operation, decision);
		return line.toString().strip();
	}

	/**
	 * Returns the file content for one launcher under a header.
	 *
	 * @param header   the file's first line
	 * @param launcher the absolute, normalized path of the launcher
	 * @param windows  {@code true} to name the launcher script through PowerShell
	 * @param update   {@code true} to add the rule for the removed {@code update} command, as CAT wrote it into
	 *                 {@code rules/cat-updater.rules}
	 * @param siblings the file names of the launchers beside {@code launcher} that a rule lets run
	 * @return the complete file content, ending with a line feed
	 */
	private static String render(String header, Path launcher, boolean windows, boolean update,
		List<String> siblings)
	{
		requireThat(launcher, "launcher").isAbsolute().isEqualTo(launcher.normalize());
		List<String> command = new ArrayList<>();
		if (windows)
			command.addAll(WINDOWS_PREFIX);
		command.add(launcher.toString());

		StringBuilder content = new StringBuilder(header).append('\n');
		rule(content, command, List.of("install", "--harness", CodexActivation.HARNESS), ALLOW);
		if (update)
			rule(content, command, List.of("update", "--harness", CodexActivation.HARNESS), ALLOW);
		rule(content, command, List.of("status"), ALLOW);
		rule(content, command, List.of("uninstall", "--harness", CodexActivation.HARNESS), UNINSTALL_DECISION);
		for (String name : siblings)
			rule(content, List.of(launcher.resolveSibling(name).toString()), List.of(), ALLOW);
		return content.toString();
	}

	/**
	 * Reports whether a file's content is exactly what {@link #render} writes for the launcher that its first rule
	 * names, what an earlier installer wrote with the runner launchers' rules instead of the request server's, or
	 * what CAT's installer wrote before it added either, so that the installer may replace the file.
	 *
	 * @param content the file's content
	 * @param windows {@code true} if the file names the launcher through PowerShell
	 * @return {@code true} if CAT wrote the content
	 * @throws NullPointerException if {@code content} is null
	 */
	public static boolean isWrittenByCat(String content, boolean windows)
	{
		return isRenderedUnder(HEADER, content, windows, false, siblings(windows)) ||
			!windows && isRenderedUnder(HEADER, content, false, false, RUNNER_LAUNCHERS) ||
			isRenderedUnder(HEADER, content, windows, false, List.of());
	}

	/**
	 * Reports whether a file's content is exactly what CAT wrote into {@code rules/cat-updater.rules} for the launcher
	 * that its first rule names, before its installer was called the installer.
	 *
	 * @param content the file's content
	 * @param windows {@code true} if the file names the launcher through PowerShell
	 * @return {@code true} if CAT wrote the content
	 * @throws NullPointerException if {@code content} is null
	 */
	public static boolean isUpdaterLeftover(String content, boolean windows)
	{
		return isRenderedUnder(UPDATER_HEADER, content, windows, true, List.of());
	}

	/**
	 * Reports whether a file's content is exactly the rules for the launcher that its first rule names, under a header.
	 *
	 * @param header  the expected first line
	 * @param content the file's content
	 * @param windows {@code true} if the file names the launcher through PowerShell
	 * @param update  {@code true} if the rules include the one for the removed {@code update} command
	 * @param siblings the file names of the launchers beside the launcher whose rules the content includes
	 * @return {@code true} if the content equals the rendered rules
	 */
	private static boolean isRenderedUnder(String header, String content, boolean windows, boolean update,
		List<String> siblings)
	{
		String[] lines = content.split("\n", -1);
		if (lines.length < 2 || !lines[0].equals(header))
			return false;
		String rule = lines[1];
		String start = "prefix_rule(pattern=";
		int end = rule.lastIndexOf("], decision=");
		if (!rule.startsWith(start) || end < start.length())
			return false;
		JsonNode pattern;
		try
		{
			pattern = MAPPER.readTree(rule.substring(start.length(), end + 1));
		}
		catch (JacksonException _)
		{
			return false;
		}
		int launcherIndex = 0;
		if (windows)
			launcherIndex = WINDOWS_PREFIX.size();
		if (!pattern.isArray() || !pattern.path(launcherIndex).isString())
			return false;
		Path launcher = Path.of(pattern.path(launcherIndex).stringValue());
		if (!launcher.isAbsolute() || !launcher.equals(launcher.normalize()))
			return false;
		return content.equals(render(header, launcher, windows, update, siblings));
	}
	/**
	 * Appends one prefix rule.
	 *
	 * @param content   the file content being built
	 * @param command   the launcher command tokens
	 * @param operation the operation tokens after the launcher
	 * @param decision  the Codex decision
	 */
	private static void rule(StringBuilder content, List<String> command, List<String> operation, String decision)
	{
		List<String> pattern = new ArrayList<>(command);
		pattern.addAll(operation);
		List<String> quoted = new ArrayList<>();
		for (String token : pattern)
			quoted.add(MAPPER.writeValueAsString(token));
		content.append("prefix_rule(pattern=[").append(String.join(", ", quoted)).append("], decision=").
			append(MAPPER.writeValueAsString(decision)).append(")\n");
	}
}
