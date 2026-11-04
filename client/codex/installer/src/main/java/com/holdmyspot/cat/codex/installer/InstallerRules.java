package com.holdmyspot.cat.codex.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes and recognizes the CAT-owned Codex execution-policy file that lets an agent run the stable installer
 * launcher.
 * <p>
 * Each rule is a {@code prefix_rule}. Codex applies a prefix rule to every command that starts with its pattern, so
 * the installer validates its complete argument list itself. No rule starts with {@code setup}, so setup always needs
 * the user.
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
	 * The fixed tokens that start every Windows launcher command, before the launcher script's path.
	 */
	private static final List<String> WINDOWS_PREFIX = List.of("powershell.exe", "-NoProfile", "-NonInteractive",
		"-ExecutionPolicy", "Bypass", "-File");
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
	 * Returns the file content that permits the agent operations on one stable launcher.
	 *
	 * @param launcher the absolute, normalized path of the stable installer launcher
	 * @param windows  {@code true} to name the launcher script through PowerShell, as Windows runs it
	 * @return the complete file content, ending with a line feed
	 * @throws NullPointerException     if {@code launcher} is null
	 * @throws IllegalArgumentException if {@code launcher} is relative or not normalized
	 */
	public static String render(Path launcher, boolean windows)
	{
		return render(launcher, windows, false);
	}

	/**
	 * Returns the file content for one stable launcher, as this build or an earlier one wrote it.
	 *
	 * @param launcher the absolute, normalized path of the stable installer launcher
	 * @param windows  {@code true} to name the launcher script through PowerShell
	 * @param earlier  {@code true} for the content of earlier builds, which also asked before a {@code rollback}
	 *                 operation that the installer no longer has
	 * @return the complete file content, ending with a line feed
	 */
	private static String render(Path launcher, boolean windows, boolean earlier)
	{
		requireThat(launcher, "launcher").isAbsolute().isEqualTo(launcher.normalize());
		List<String> command = new ArrayList<>();
		if (windows)
			command.addAll(WINDOWS_PREFIX);
		command.add(launcher.toString());

		StringBuilder content = new StringBuilder(HEADER).append('\n');
		rule(content, command, List.of("install", "--harness", CodexActivation.HARNESS), ALLOW);
		rule(content, command, List.of("update", "--harness", CodexActivation.HARNESS), ALLOW);
		rule(content, command, List.of("status"), ALLOW);
		if (earlier)
			rule(content, command, List.of("rollback", "--harness", CodexActivation.HARNESS), UNINSTALL_DECISION);
		rule(content, command, List.of("uninstall", "--harness", CodexActivation.HARNESS), UNINSTALL_DECISION);
		return content.toString();
	}

	/**
	 * Reports whether a file's content is exactly what {@link #render} writes for the launcher that its first rule
	 * names, or what earlier builds wrote for it, so that the installer replaces their file.
	 *
	 * @param content the file's content
	 * @param windows {@code true} if the file names the launcher through PowerShell
	 * @return {@code true} if CAT wrote the content
	 * @throws NullPointerException if {@code content} is null
	 */
	public static boolean isWrittenByCat(String content, boolean windows)
	{
		String[] lines = content.split("\n", -1);
		if (lines.length < 2 || !lines[0].equals(HEADER))
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
		return content.equals(render(launcher, windows)) || content.equals(render(launcher, windows, true));
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
