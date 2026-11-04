package com.holdmyspot.cat.common.updater.cli.internal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests that every launcher that starts the updater's JVM starts it with the same options, so that the updater runs
 * alike whichever launcher the platform uses. Each launcher writes its own copy of the option list in its own
 * language; the paths in it are compared by the file that they name.
 */
public final class LauncherOptionsTest
{
	/**
	 * The folder of the launcher templates, relative to this module's folder.
	 */
	private static final Path TEMPLATES = Path.of("src/main/resources/com/holdmyspot/cat/common/updater/cli");
	/**
	 * The start of the PowerShell option list.
	 */
	private static final String POWERSHELL_START = "$javaArguments = @(";
	/**
	 * The start of the POSIX shell command that replaces the launcher with the updater's JVM.
	 */
	private static final String SHELL_START = "exec \"$java\" ";

	/**
	 * Creates the test.
	 */
	public LauncherOptionsTest()
	{
	}

	/**
	 * The Windows stable launcher, the Windows versioned launcher and the POSIX versioned launcher start the updater
	 * with the same JVM options, module path and main module.
	 *
	 * @throws IOException if a template cannot be read
	 */
	@Test
	public void launchersStartUpdaterAlike() throws IOException
	{
		List<String> posix = shellOptions(template("versioned-launcher.sh"));

		requireThat(powerShellOptions(template("versioned-launcher.ps1")), "versionedPowerShell").
			isEqualTo(posix, "versionedShell");
		requireThat(powerShellOptions(template("stable-launcher.ps1")), "stablePowerShell").
			isEqualTo(posix, "versionedShell");
		requireThat(posix, "versionedShell").contains("-m").contains("--module-path");
	}

	/**
	 * The POSIX stable launcher starts the updater through the versioned launcher, so it holds no option list of its
	 * own that could differ.
	 *
	 * @throws IOException if the template cannot be read
	 */
	@Test
	public void posixStableLauncherHasNoOptionList() throws IOException
	{
		String launcher = template("stable-launcher.sh");

		requireThat(launcher, "stableShell").doesNotContain("--module-path").doesNotContain("-Xshare");
	}

	/**
	 * Returns the JVM options of a PowerShell launcher.
	 *
	 * @param template the launcher
	 * @return the options, with paths reduced by {@link #canonical(String)}
	 */
	private static List<String> powerShellOptions(String template)
	{
		int start = template.indexOf(POWERSHELL_START);
		requireThat(start, "start").isNotNegative();
		start += POWERSHELL_START.length();
		List<String> items = new ArrayList<>();
		StringBuilder item = new StringBuilder();
		char quote = 0;
		int depth = 0;
		for (int index = start; index < template.length(); ++index)
		{
			char character = template.charAt(index);
			if (quote != 0)
			{
				if (character == quote)
					quote = 0;
			}
			else if (character == '\'' || character == '"')
				quote = character;
			else if (character == '(')
				++depth;
			else if (character == ')')
			{
				if (depth == 0)
				{
					items.add(item.toString().strip());
					break;
				}
				--depth;
			}
			else if (character == ',' && depth == 0)
			{
				items.add(item.toString().strip());
				item.setLength(0);
				continue;
			}
			item.append(character);
		}
		List<String> options = new ArrayList<>();
		for (String text : items)
			options.add(canonical(unquote(text)));
		return options;
	}

	/**
	 * Returns the JVM options of a POSIX shell launcher, without the forwarded arguments.
	 *
	 * @param template the launcher
	 * @return the options, with paths reduced by {@link #canonical(String)}
	 */
	private static List<String> shellOptions(String template)
	{
		int start = template.indexOf(SHELL_START);
		requireThat(start, "start").isNotNegative();
		StringBuilder command = new StringBuilder();
		for (String line : template.substring(start + SHELL_START.length()).split("\n", -1))
		{
			if (!line.endsWith("\\"))
			{
				command.append(line);
				break;
			}
			command.append(line, 0, line.length() - 1).append(' ');
		}
		List<String> options = new ArrayList<>();
		for (String word : command.toString().strip().split("\\s+"))
		{
			if (!word.equals("\"$@\""))
				options.add(canonical(word.replace("\"", "")));
		}
		return options;
	}

	/**
	 * Removes the quotes around a PowerShell string.
	 *
	 * @param text the item
	 * @return the item without its surrounding quotes
	 */
	private static String unquote(String text)
	{
		if (text.length() >= 2 && (text.charAt(0) == '\'' || text.charAt(0) == '"') &&
			text.charAt(text.length() - 1) == text.charAt(0))
		{
			return text.substring(1, text.length() - 1);
		}
		return text;
	}

	/**
	 * Reduces an option that names a path built from a variable to the option's literal start and the path's file
	 * name, because each launcher builds the path in its own language.
	 *
	 * @param option the option without its quotes
	 * @return the option, or {@code <literal start><path>/<file name>} when it holds a variable
	 */
	private static String canonical(String option)
	{
		int variable = option.indexOf('$');
		if (variable < 0)
			return option;
		String literal = option.substring(0, variable).replace("(Join-Path ", "");
		int end = option.length();
		while (end > variable && "')\"".indexOf(option.charAt(end - 1)) >= 0)
			--end;
		int nameStart = end;
		while (nameStart > variable && "/\\' ".indexOf(option.charAt(nameStart - 1)) < 0)
			--nameStart;
		return literal + "<path>/" + option.substring(nameStart, end);
	}

	/**
	 * Reads a launcher template.
	 *
	 * @param name the template's file name
	 * @return the template with Unix line endings
	 * @throws IOException if the template cannot be read
	 */
	private static String template(String name) throws IOException
	{
		return Files.readString(moduleFolder().resolve(TEMPLATES).resolve(name)).replace("\r\n", "\n");
	}

	/**
	 * Returns this module's folder.
	 *
	 * @return the folder
	 */
	private static Path moduleFolder()
	{
		try
		{
			Path classes = Path.of(LauncherOptionsTest.class.getProtectionDomain().getCodeSource().getLocation().
				toURI());
			// target/test-classes is two levels below the module
			return classes.resolve("../..").normalize();
		}
		catch (URISyntaxException e)
		{
			throw new AssertionError(e);
		}
	}
}
