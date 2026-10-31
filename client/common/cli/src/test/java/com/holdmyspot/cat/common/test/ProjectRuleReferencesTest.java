package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests that this repository's project rules name every rule they reference in a form that does not depend on the
 * reader's location.
 */
public final class ProjectRuleReferencesTest
{
	/**
	 * Matches a path in backticks that ends in {@code .md}, capturing the path.
	 */
	private static final Pattern REFERENCE = Pattern.compile("`([^`]+\\.md)`");

	/**
	 * Creates a test instance.
	 */
	public ProjectRuleReferencesTest()
	{
	}

	/**
	 * Verifies that no project rule body names an existing Markdown file through a path relative to the rule's own
	 * directory or to the project's rule directory. An agent can resolve such a path against the wrong directory, so
	 * each project rule names another file through {@code ${CAT_PROJECT_ROOT}} or {@code ${CAT_PLUGIN_ROOT}}.
	 * Frontmatter is exempt, because the loader resolves its {@code depends-on} entries itself.
	 *
	 * @throws IOException if a rule cannot be read
	 */
	@Test
	public void projectRulesNameReferencedFilesFromRoot() throws IOException
	{
		Path rules = clientDirectory().resolveSibling(".cat").resolve("rules");
		requireThat(rules, "rules").isDirectory();
		List<String> relative = new ArrayList<>();
		for (Path file : markdownFiles(rules))
		{
			String content = Files.readString(file);
			int bodyStart = bodyStart(content);
			Matcher matcher = REFERENCE.matcher(content);
			matcher.region(bodyStart, content.length());
			while (matcher.find())
			{
				String reference = matcher.group(1);
				if (!namesExistingFile(reference, file.getParent()) && !namesExistingFile(reference, rules))
					continue;
				long line = content.substring(0, matcher.start()).chars().filter(c -> c == '\n').count() + 1;
				relative.add(rules.relativize(file) + ":" + line + ": `" + reference + "`");
			}
		}
		requireThat(relative, "relativeReferences").isEmpty();
	}

	/**
	 * Returns the {@code client} directory that the build passes in.
	 *
	 * @return the directory
	 */
	private static Path clientDirectory()
	{
		String value = System.getProperty("cat.client.directory");
		requireThat(value, "cat.client.directory").isNotNull();
		return Path.of(value).toAbsolutePath().normalize();
	}

	/**
	 * Returns the Markdown files below a directory.
	 *
	 * @param directory the directory
	 * @return the files, sorted
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<Path> markdownFiles(Path directory) throws IOException
	{
		try (Stream<Path> paths = Files.walk(directory))
		{
			return paths.filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS)).
				filter(path -> path.getFileName().toString().endsWith(".md")).
				sorted().
				toList();
		}
	}

	/**
	 * Returns where a rule's body starts.
	 *
	 * @param content the rule's content
	 * @return the index after the closing frontmatter delimiter, or {@code 0} if the rule has no frontmatter
	 */
	private static int bodyStart(String content)
	{
		if (!content.startsWith("---\n"))
			return 0;
		String delimiter = "\n---\n";
		int end = content.indexOf(delimiter, 4);
		requireThat(end, "frontmatterEnd").isNotNegative();
		return end + delimiter.length();
	}

	/**
	 * Returns whether a reference names an existing file when it is resolved against a directory.
	 *
	 * @param reference the reference
	 * @param directory the directory
	 * @return {@code false} if the reference starts with a root notation such as {@code ${CAT_PROJECT_ROOT}}, holds a
	 *   placeholder, is absolute, or names no regular file
	 */
	private static boolean namesExistingFile(String reference, Path directory)
	{
		if (reference.startsWith("$") || reference.contains("<"))
			return false;
		Path path;
		try
		{
			path = Path.of(reference);
		}
		catch (InvalidPathException _)
		{
			return false;
		}
		return !path.isAbsolute() && Files.isRegularFile(directory.resolve(path).normalize(), NOFOLLOW_LINKS);
	}
}
