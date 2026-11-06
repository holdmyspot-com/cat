package com.holdmyspot.cat.claude.policy.test;

import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that CAT recognizes every option that the {@code --help} output of the Claude Code release that it reads lists,
 * takes as many arguments for each as Claude Code does, and refuses the options whose effect on the sandbox it cannot
 * read.
 */
public final class ClaudeOptionTableTest
{
	/**
	 * An option line of the help output: the spellings of the option, then its value in {@code <>} or {@code []}.
	 */
	private static final Pattern OPTION_LINE = Pattern.compile(
		"^  ((?:-[A-Za-z0-9-]+, )*-[A-Za-z0-9-]+)(?: ([<\\[][^>\\]]*[>\\]]))?(?: .*)?$");
	/**
	 * An option that no release lists, which ends each tested command line.
	 */
	private static final String MARKER = "--cat-marker-option";
	/**
	 * The options that change the sandbox in a way that CAT cannot read.
	 */
	private static final Set<String> UNSUPPORTED = Set.of("--bare", "--cloud", "--desktop", "--environment",
		"--safe-mode", "--teleport", "--tmux", "--worktree");

	/**
	 * Creates the test.
	 */
	public ClaudeOptionTableTest()
	{
	}

	/**
	 * Verifies that each option of the recorded help output is recognized, consumes the arguments that Claude Code
	 * gives it so that the next option is parsed as an option, and is refused only if CAT cannot read its effect.
	 *
	 * @throws IOException if the help output cannot be read
	 */
	@Test
	public void everyListedOptionIsKnown() throws IOException
	{
		String help;
		try (InputStream in = ClaudeOptionTableTest.class.getResourceAsStream("/policy/claude-2.1.318-help.txt"))
		{
			requireThat(in, "help").isNotNull();
			help = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		help = help.substring(0, help.indexOf("\nCommands:"));
		Set<String> refused = new TreeSet<>();
		int options = 0;
		for (String line : help.split("\n"))
		{
			Matcher matcher = OPTION_LINE.matcher(line);
			if (!matcher.matches())
				continue;
			++options;
			List<String> names = Arrays.asList(matcher.group(1).split(", "));
			String value = matcher.group(2);
			for (String name : names)
			{
				List<String> arguments = new ArrayList<>();
				arguments.add(name);
				if (value != null)
				{
					if (name.equals("--setting-sources"))
						arguments.add("user");
					else if (name.equals("--settings"))
						arguments.add("{}");
					else if (value.endsWith("...>"))
						arguments.addAll(List.of("first", "second"));
					else if (value.startsWith("<"))
					{
						// A required value is taken even when it starts with a dash
						arguments.add("-value");
					}
					else
						arguments.add("value");
				}
				arguments.add(MARKER);
				String refusal = refusal(arguments);
				if (refusal.contains(MARKER))
					continue;
				requireThat(refusal, "refusal").contains("whose effect on the session's sandbox CAT cannot read");
				refused.add(names.getLast());
			}
		}
		requireThat(options, "options").isGreaterThanOrEqualTo(60);
		requireThat(refused, "refused").isEqualTo(new TreeSet<>(UNSUPPORTED));
	}

	/**
	 * Reads the policy of a session whose command line the test chooses, which the test expects to be refused.
	 *
	 * @param arguments the command line
	 * @return the refusal's message
	 * @throws IOException if the fixture cannot be created
	 */
	private static String refusal(List<String> arguments) throws IOException
	{
		try (ClaudeSessionFixture fixture = new ClaudeSessionFixture())
		{
			fixture.arguments.addAll(arguments);
			try
			{
				fixture.policy();
			}
			catch (PolicyRefusal e)
			{
				return e.getMessage();
			}
			throw new AssertionError("The command line was not refused: " + arguments);
		}
	}
}
