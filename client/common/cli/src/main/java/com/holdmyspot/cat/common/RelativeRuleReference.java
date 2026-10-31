package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A reference in a project or user rule that names an existing file relative to the rule's own directory or rule
 * directory, which an agent that reads the rule can resolve against the wrong directory.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param rule        the absolute, normalized file of the rule that holds the reference
 * @param line        the 1-based line of the reference in {@code rule}
 * @param reference   the reference as written
 * @param replacement the same file named from the root that the session context states, such as
 *                    {@code ${CAT_PROJECT_ROOT}/.cat/rules/include/x.md}
 */
public record RelativeRuleReference(Path rule, int line, String reference, String replacement)
{
	/**
	 * Matches a path in backticks that ends in {@code .md}, capturing the path.
	 */
	private static final Pattern REFERENCE = Pattern.compile("`([^`]+\\.md)`");
	/**
	 * The notation that names the project directory.
	 */
	private static final String PROJECT_ROOT = "${CAT_PROJECT_ROOT}";
	/**
	 * The notation that names the user rule directory.
	 */
	private static final String USER_RULES = "${CAT_USER_RULES}";
	/**
	 * The number of references that a warning names.
	 */
	private static final int MAXIMUM_LISTED = 20;

	/**
	 * Creates a relative rule reference.
	 *
	 * @param rule        the absolute, normalized file of the rule that holds the reference
	 * @param line        the 1-based line of the reference in {@code rule}
	 * @param reference   the reference as written
	 * @param replacement the same file named from the root that the session context states
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code rule} is not absolute, {@code line} is not positive, or
	 *                                  {@code reference} or {@code replacement} is blank
	 */
	public RelativeRuleReference
	{
		requireThat(rule, "rule").isNotNull();
		requireThat(rule.isAbsolute(), "rule.isAbsolute()").isTrue();
		requireThat(line, "line").isPositive();
		requireThat(reference, "reference").isNotBlank();
		requireThat(replacement, "replacement").isNotBlank();
	}

	/**
	 * Finds the relative references in the Markdown files of a project's rule directory and of a user rule directory.
	 * <p>
	 * A file that cannot be read, or that exceeds the size of a rule that the loader accepts, is skipped, because these
	 * references only produce warnings.
	 *
	 * @param userRulesRoot the user rule directory, or {@code null} when the session has none
	 * @param projectRoot   the project directory, whose {@code .cat/rules} directory is searched
	 * @return the references, by rule file and then by position
	 * @throws NullPointerException if {@code projectRoot} is null
	 */
	public static List<RelativeRuleReference> find(Path userRulesRoot, Path projectRoot)
	{
		requireThat(projectRoot, "projectRoot").isNotNull();
		List<RelativeRuleReference> references = new ArrayList<>();
		if (userRulesRoot != null)
		{
			Path userRules = userRulesRoot.toAbsolutePath().normalize();
			for (Path file : markdownFiles(userRules))
				references.addAll(inRule(file, userRules, userRules, USER_RULES));
		}
		Path project = projectRoot.toAbsolutePath().normalize();
		Path projectRules = ProjectRuleLoader.rulesDirectory(project);
		for (Path file : markdownFiles(projectRules))
			references.addAll(inRule(file, projectRules, project, PROJECT_ROOT));
		return references;
	}

	/**
	 * Returns the warning that tells the user which rule references to rewrite.
	 * <p>
	 * The warning names at most {@value #MAXIMUM_LISTED} references, so it stays short enough for a harness to show,
	 * and counts the rest.
	 *
	 * @param references the references
	 * @return the warning
	 * @throws NullPointerException     if {@code references} is null
	 * @throws IllegalArgumentException if {@code references} is empty
	 */
	public static String warning(List<RelativeRuleReference> references)
	{
		requireThat(references, "references").isNotEmpty();
		StringJoiner warning = new StringJoiner("\n");
		warning.add("CAT: these rule references name a file relative to the rule's own directory, which agents can " +
			"resolve against the wrong directory. Name each file from the root that CAT states instead:");
		for (RelativeRuleReference reference : references.subList(0, Math.min(references.size(), MAXIMUM_LISTED)))
			warning.add("- " + reference.message());
		if (references.size() > MAXIMUM_LISTED)
			warning.add("- and " + (references.size() - MAXIMUM_LISTED) + " more");
		return warning.toString();
	}

	/**
	 * Returns this reference as one warning line for the user.
	 *
	 * @return the rule file, line, reference and replacement
	 */
	public String message()
	{
		return rule + ":" + line + ": `" + reference + "` is relative to the rule's directory; write `" +
			replacement + "`";
	}

	/**
	 * Lists the Markdown files below a directory.
	 *
	 * @param directory the directory
	 * @return the regular files, sorted, or an empty list if the directory is missing or cannot be listed
	 */
	private static List<Path> markdownFiles(Path directory)
	{
		if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
			return List.of();
		try (Stream<Path> paths = Files.walk(directory))
		{
			return paths.filter(path -> path.getFileName().toString().endsWith(".md")).
				filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS)).
				sorted().
				toList();
		}
		catch (IOException | UncheckedIOException _)
		{
			return List.of();
		}
	}

	/**
	 * Finds the relative references in one rule file's body.
	 *
	 * @param file     the rule file
	 * @param ruleRoot the rule directory that holds {@code file}
	 * @param root     the directory that {@code notation} names
	 * @param notation the notation that names {@code root}
	 * @return the references, or an empty list if the file cannot be read
	 */
	private static List<RelativeRuleReference> inRule(Path file, Path ruleRoot, Path root, String notation)
	{
		String content;
		try
		{
			if (Files.size(file) > ProjectRuleLoader.MAXIMUM_RULE_BYTES)
				return List.of();
			content = Files.readString(file, UTF_8);
		}
		catch (IOException _)
		{
			return List.of();
		}
		List<RelativeRuleReference> references = new ArrayList<>();
		Matcher matcher = REFERENCE.matcher(content);
		matcher.region(bodyStart(content), content.length());
		while (matcher.find())
		{
			String reference = matcher.group(1);
			Path target = existingFile(reference, file.getParent());
			if (target == null)
				target = existingFile(reference, ruleRoot);
			if (target == null || !target.startsWith(root))
				continue;
			int line = (int) content.substring(0, matcher.start()).chars().filter(c -> c == '\n').count() + 1;
			StringJoiner replacement = new StringJoiner("/", notation + "/", "");
			for (Path name : root.relativize(target))
				replacement.add(name.toString());
			references.add(new RelativeRuleReference(file, line, reference, replacement.toString()));
		}
		return references;
	}

	/**
	 * Returns where a rule's body starts, after its frontmatter, whose {@code depends-on} entries are relative by
	 * design.
	 *
	 * @param content the rule's content
	 * @return the index after the closing frontmatter delimiter, or {@code 0} if the rule has no complete frontmatter
	 */
	private static int bodyStart(String content)
	{
		String lineEnding;
		if (content.startsWith("---\n"))
			lineEnding = "\n";
		else if (content.startsWith("---\r\n"))
			lineEnding = "\r\n";
		else
			return 0;
		String delimiter = lineEnding + "---" + lineEnding;
		int end = content.indexOf(delimiter, 3 + lineEnding.length());
		if (end < 0)
			return 0;
		return end + delimiter.length();
	}

	/**
	 * Returns the existing file that a reference names when it is resolved against a directory.
	 *
	 * @param reference the reference
	 * @param directory the directory
	 * @return the absolute, normalized regular file, or {@code null} if the reference starts with a root notation,
	 *   holds a placeholder, is absolute, or names no regular file
	 */
	private static Path existingFile(String reference, Path directory)
	{
		if (reference.startsWith("$") || reference.contains("<"))
			return null;
		Path path;
		try
		{
			path = Path.of(reference);
		}
		catch (InvalidPathException _)
		{
			return null;
		}
		if (path.isAbsolute())
			return null;
		Path resolved = directory.resolve(path).normalize();
		if (!Files.isRegularFile(resolved, NOFOLLOW_LINKS))
			return null;
		return resolved;
	}
}
