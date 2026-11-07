package com.holdmyspot.cat.codex.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.EntryGroup;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes and recognizes the CAT-owned Codex execution-policy file that lets an agent run the installer launcher of the
 * active version folder, and recognizes the runner rules that CAT's installation guide told users to write.
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
	 * The file names of the runner launchers that CAT's installation guide told users to write rules for.
	 */
	private static final Set<String> RUNNER_LAUNCHERS = Set.of("claude-runner", "codex-runner", "claude-runner.cmd",
		"codex-runner.cmd");
	/**
	 * The arguments that a runner rule of CAT's installation guide may pass.
	 */
	private static final Set<String> RUNNER_RULE_ARGUMENTS = Set.of("pattern", "decision", "justification");
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
	 * Returns the file content that permits the agent operations on one installer launcher.
	 *
	 * @param launcher the absolute, normalized path of the installer launcher in the active version folder
	 * @param windows  {@code true} to name the launcher script through PowerShell, as Windows runs it
	 * @return the complete file content, ending with a line feed
	 * @throws NullPointerException     if {@code launcher} is null
	 * @throws IllegalArgumentException if {@code launcher} is relative or not normalized
	 */
	public static String render(Path launcher, boolean windows)
	{
		return render(HEADER, launcher, windows, false);
	}

	/**
	 * Groups the rules that {@link #render} writes for a launcher by what they let Codex do. Codex runs an operation
	 * that a rule allows without asking and outside its sandbox, and runs the operation of the rule that keeps its
	 * prompt outside its sandbox once the user agrees.
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
		Map<String, String> allowed = Map.of(
			ruleLine(command, List.of("install", "--harness", CodexActivation.HARNESS), ALLOW), "install",
			ruleLine(command, List.of("status"), ALLOW), "status");
		Map<String, String> prompted = Map.of(
			ruleLine(command, List.of("uninstall", "--harness", CodexActivation.HARNESS), UNINSTALL_DECISION),
			"uninstall");

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
	 * @return the complete file content, ending with a line feed
	 */
	private static String render(String header, Path launcher, boolean windows, boolean update)
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
		return content.toString();
	}

	/**
	 * Reports whether a file's content is exactly what {@link #render} writes for the launcher that its first rule
	 * names, so that the installer may replace the file.
	 *
	 * @param content the file's content
	 * @param windows {@code true} if the file names the launcher through PowerShell
	 * @return {@code true} if CAT wrote the content
	 * @throws NullPointerException if {@code content} is null
	 */
	public static boolean isWrittenByCat(String content, boolean windows)
	{
		return isRenderedUnder(HEADER, content, windows, false);
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
		return isRenderedUnder(UPDATER_HEADER, content, windows, true);
	}

	/**
	 * Reports whether a file's content is exactly the rules for the launcher that its first rule names, under a header.
	 *
	 * @param header  the expected first line
	 * @param content the file's content
	 * @param windows {@code true} if the file names the launcher through PowerShell
	 * @param update  {@code true} if the rules include the one for the removed {@code update} command
	 * @return {@code true} if the content equals the rendered rules
	 */
	private static boolean isRenderedUnder(String header, String content, boolean windows, boolean update)
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
		return content.equals(render(header, launcher, windows, update));
	}
	/**
	 * Finds the runner rules that CAT's installation guide told users to write: each {@code prefix_rule} call whose
	 * {@code pattern} is a list of one string that names a runner launcher in {@code bin} of a version folder, whose
	 * {@code decision} is {@code "allow"}, and whose only other argument is an optional {@code justification} string.
	 * Spacing, line breaks, trailing commas, a {@code ;} after a call, argument order and quote style may vary.
	 *
	 * @param content        a rules file's content
	 * @param versionFolders the folder that holds CAT's version folders
	 * @return the runner rules, and whether the content holds anything else besides blank space
	 * @throws NullPointerException if any of the arguments are null
	 */
	static RunnerRules findRunnerRules(String content, Path versionFolders)
	{
		requireThat(content, "content").isNotNull();
		requireThat(versionFolders, "versionFolders").isNotNull();
		List<String> rules = new ArrayList<>();
		boolean other = false;
		int position = skipBlank(content, 0);
		while (position < content.length())
		{
			int end = -1;
			if (content.startsWith(RULE_FUNCTION, position))
				end = groupEnd(content, skipBlank(content, position + RULE_FUNCTION.length()));
			if (end < 0)
			{
				// Not a rule: skip the line and keep looking for runner rules to name
				other = true;
				int lineEnd = content.indexOf('\n', position);
				if (lineEnd < 0)
					lineEnd = content.length();
				position = skipBlank(content, lineEnd);
				continue;
			}
			String rule = content.substring(position, end);
			if (isRunnerRule(rule, versionFolders))
				rules.add(rule);
			else
				other = true;
			position = skipBlank(content, end);
			if (content.startsWith(";", position))
				position = skipBlank(content, position + 1);
		}
		return new RunnerRules(List.copyOf(rules), other);
	}

	/**
	 * Reports whether a {@code prefix_rule} call is a runner rule of CAT's installation guide.
	 *
	 * @param rule           the call, from its function name through its closing parenthesis
	 * @param versionFolders the folder that holds CAT's version folders
	 * @return true if the call is a runner rule
	 */
	private static boolean isRunnerRule(String rule, Path versionFolders)
	{
		String arguments = rule.substring(rule.indexOf('(') + 1, rule.length() - 1);
		Map<String, String> values = new HashMap<>();
		for (String argument : split(arguments))
		{
			int equals = argument.indexOf('=');
			if (equals < 0)
				return false;
			String name = argument.substring(0, equals).strip();
			if (!RUNNER_RULE_ARGUMENTS.contains(name) || values.put(name, argument.substring(equals + 1).strip()) != null)
				return false;
		}
		String pattern = values.get("pattern");
		if (pattern == null || !"allow".equals(stringValue(values.get("decision"))) ||
			values.containsKey("justification") && stringValue(values.get("justification")) == null ||
			!pattern.startsWith("[") || !pattern.endsWith("]"))
		{
			return false;
		}
		List<String> tokens = split(pattern.substring(1, pattern.length() - 1));
		if (tokens.size() != 1)
			return false;
		String launcher = stringValue(tokens.getFirst());
		return launcher != null && isRunnerLauncher(launcher, versionFolders);
	}

	/**
	 * Reports whether a path names a runner launcher in {@code bin} of one of CAT's version folders, spelled exactly
	 * as that path.
	 *
	 * @param text           the path
	 * @param versionFolders the folder that holds CAT's version folders
	 * @return true if {@code text} names {@code claude-runner} or {@code codex-runner}, with or without {@code .cmd}
	 */
	private static boolean isRunnerLauncher(String text, Path versionFolders)
	{
		Path launcher;
		try
		{
			launcher = Path.of(text);
		}
		catch (InvalidPathException _)
		{
			return false;
		}
		// Windows accepts either separator; any other difference in spelling is not the documented rule
		if (!launcher.isAbsolute() || !launcher.equals(launcher.normalize()) ||
			!launcher.toString().equals(text.replace('/', File.separatorChar)))
		{
			return false;
		}
		Path bin = launcher.getParent();
		if (!RUNNER_LAUNCHERS.contains(launcher.getFileName().toString()) || bin == null ||
			bin.getFileName() == null || !bin.getFileName().toString().equals("bin"))
		{
			return false;
		}
		Path versionFolder = bin.getParent();
		return versionFolder != null && versionFolders.equals(versionFolder.getParent());
	}

	/**
	 * Splits a call's arguments or a list's elements at the commas outside strings and brackets, dropping an empty
	 * element after a trailing comma.
	 *
	 * @param text the text between the parentheses or brackets
	 * @return the elements, stripped; empty when the text is blank
	 */
	private static List<String> split(String text)
	{
		List<String> elements = new ArrayList<>();
		int depth = 0;
		int start = 0;
		int index = 0;
		while (index < text.length())
		{
			char character = text.charAt(index);
			if (character == '"' || character == '\'')
			{
				index = stringEnd(text, index);
				if (index < 0)
					return List.of("");
				continue;
			}
			if (character == '(' || character == '[' || character == '{')
				++depth;
			else if (character == ')' || character == ']' || character == '}')
				--depth;
			else if (character == ',' && depth == 0)
			{
				elements.add(text.substring(start, index).strip());
				start = index + 1;
			}
			++index;
		}
		String last = text.substring(start).strip();
		if (!last.isEmpty() || !elements.isEmpty() && elements.getLast().isEmpty())
			elements.add(last);
		return elements;
	}

	/**
	 * Returns the value of a string literal that uses single or double quotes and the escapes {@code \\},
	 * {@code \"}, {@code \'}, {@code \n} and {@code \t}.
	 *
	 * @param literal the stripped literal, or null
	 * @return the value, or null if {@code literal} is null or not such a literal
	 */
	private static String stringValue(String literal)
	{
		if (literal == null || literal.length() < 2 || literal.charAt(0) != '"' && literal.charAt(0) != '\'' ||
			stringEnd(literal, 0) != literal.length())
			return null;
		StringBuilder value = new StringBuilder();
		for (int index = 1; index < literal.length() - 1; ++index)
		{
			char character = literal.charAt(index);
			if (character == '\\')
			{
				++index;
				character = switch (literal.charAt(index))
				{
					case 'n' -> '\n';
					case 't' -> '\t';
					case '\\', '"', '\'' -> literal.charAt(index);
					default -> 0;
				};
				if (character == 0)
					return null;
			}
			value.append(character);
		}
		return value.toString();
	}

	/**
	 * Finds the end of a string literal on one line.
	 *
	 * @param text  the text
	 * @param start the index of the literal's opening quote
	 * @return the index after the closing quote, or -1 if the line ends first
	 */
	private static int stringEnd(String text, int start)
	{
		char quote = text.charAt(start);
		int index = start + 1;
		while (index < text.length())
		{
			char character = text.charAt(index);
			if (character == '\n')
				return -1;
			if (character == quote)
				return index + 1;
			if (character == '\\')
				++index;
			++index;
		}
		return -1;
	}

	/**
	 * Finds the end of a parenthesized group.
	 *
	 * @param text  the text
	 * @param start the index at which the group's opening parenthesis must be
	 * @return the index after the matching closing parenthesis, or -1 if {@code start} holds no opening parenthesis or
	 * 	the group does not end
	 */
	private static int groupEnd(String text, int start)
	{
		if (start >= text.length() || text.charAt(start) != '(')
			return -1;
		int depth = 0;
		int index = start;
		while (index < text.length())
		{
			char character = text.charAt(index);
			if (character == '"' || character == '\'')
			{
				index = stringEnd(text, index);
				if (index < 0)
					return -1;
				continue;
			}
			if (character == '(' || character == '[' || character == '{')
				++depth;
			else if (character == ')' || character == ']' || character == '}')
			{
				--depth;
				if (depth == 0)
					return index + 1;
			}
			++index;
		}
		return -1;
	}

	/**
	 * Skips spaces, tabs and line breaks.
	 *
	 * @param text  the text
	 * @param start the index to start at
	 * @return the index of the first other character, or the text's length
	 */
	private static int skipBlank(String text, int start)
	{
		int index = start;
		while (index < text.length() && " \t\r\n".indexOf(text.charAt(index)) >= 0)
			++index;
		return index;
	}

	/**
	 * The runner rules that a rules file holds.
	 *
	 * @param rules each runner rule's text, in file order
	 * @param other true if the file holds anything besides the runner rules and blank space
	 */
	record RunnerRules(List<String> rules, boolean other)
	{
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
