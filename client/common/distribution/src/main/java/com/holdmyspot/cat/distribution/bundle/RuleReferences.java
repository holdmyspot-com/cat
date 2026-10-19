package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.ProjectRuleLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Relocates and checks the references that packaged Markdown files make to packaged rules.
 * <p>
 * A reference is a path in backticks that ends in {@code .md}. It names a packaged file either through the plugin
 * directory, as {@code ${CAT_PLUGIN_ROOT}/rules/...} or the plugin-root directive, or relative to the directory of the
 * file that holds it.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class RuleReferences
{
	/**
	 * Matches a path in backticks that ends in {@code .md}, capturing the path.
	 */
	private static final Pattern REFERENCE = Pattern.compile("`([^`]+\\.md)`");
	/**
	 * The notations that start a reference through the plugin directory, each followed by {@code /}.
	 */
	private static final List<String> PLUGIN_ROOTS = List.of("${CAT_PLUGIN_ROOT}",
		ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE);
	private static final int MAXIMUM_INPUT_BYTES = 1_048_576;

	/**
	 * Prevents instantiation.
	 */
	private RuleReferences()
	{
	}

	/**
	 * Rewrites a rule's relative references for a new location of the rule, so that each one still names the file it
	 * named before the move. A reference that does not name a packaged rule from the old location stays as written,
	 * such as an example path in the rule's prose.
	 *
	 * @param body   the rule's content
	 * @param source the rule's old location
	 * @param target the rule's new location
	 * @param rules  the artifact's rule directory
	 * @return the content with relocated references
	 */
	static String relocate(String body, Path source, Path target, Path rules)
	{
		Matcher matcher = REFERENCE.matcher(body);
		StringBuilder result = new StringBuilder(body.length());
		while (matcher.find())
		{
			Optional<Path> named = relative(matcher.group(1), source).
				filter(path -> path.startsWith(rules) && Files.isRegularFile(path, NOFOLLOW_LINKS));
			if (named.isEmpty())
				continue;
			String relocated = slashSeparated(target.getParent().relativize(named.get()));
			matcher.appendReplacement(result, Matcher.quoteReplacement("`" + relocated + "`"));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/**
	 * Rejects an assembled plugin whose Markdown files reference a missing packaged rule. A reference through the
	 * plugin directory must name a packaged file. A relative reference must name a packaged file when its file name is
	 * that of a packaged rule; other relative references, such as a project's {@code README.md}, name files outside the
	 * plugin and are not checked.
	 *
	 * @param artifact the assembled plugin
	 * @throws NullPointerException if {@code artifact} is null
	 * @throws IOException          if a reference names a missing file, listing each such reference with its file and
	 *                              line, or if a file cannot be read
	 */
	public static void requireResolvable(Path artifact) throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		Set<String> ruleNames = ruleNames(artifact.resolve("rules"));
		List<String> missing = new ArrayList<>();
		try (Stream<Path> files = Files.walk(artifact))
		{
			for (Path file : files.filter(RuleReferences::isMarkdown).sorted().toList())
			{
				String content = BoundedInput.readUtf8(file, MAXIMUM_INPUT_BYTES, "Packaged Markdown file");
				Matcher matcher = REFERENCE.matcher(content);
				while (matcher.find())
				{
					String reference = matcher.group(1);
					Optional<Path> named = named(reference, file, artifact, ruleNames);
					if (named.isPresent() && !Files.isRegularFile(named.get(), NOFOLLOW_LINKS))
					{
						long line = content.substring(0, matcher.start()).chars().filter(c -> c == '\n').count() + 1;
						missing.add(slashSeparated(artifact.relativize(file)) + ":" + line + ": `" + reference + "`");
					}
				}
			}
		}
		if (!missing.isEmpty())
		{
			throw new IOException("These references in the plugin " + artifact + " name files that it does not " +
				"hold:\n" + String.join("\n", missing));
		}
	}

	/**
	 * Returns the file names of an artifact's packaged rules.
	 *
	 * @param rules the artifact's rule directory
	 * @return the file names, or an empty set if the artifact has no rule directory
	 * @throws IOException if the directory cannot be listed
	 */
	private static Set<String> ruleNames(Path rules) throws IOException
	{
		Set<String> names = new HashSet<>();
		if (!Files.isDirectory(rules, NOFOLLOW_LINKS))
			return names;
		try (Stream<Path> files = Files.walk(rules))
		{
			for (Path file : files.filter(RuleReferences::isMarkdown).toList())
				names.add(file.getFileName().toString());
		}
		return names;
	}

	/**
	 * Returns the packaged file that a reference must name.
	 *
	 * @param reference the reference
	 * @param file      the file that holds the reference
	 * @param artifact  the assembled plugin
	 * @param ruleNames the file names of the plugin's rules
	 * @return the file that the reference names, or empty if the reference is not checked
	 */
	private static Optional<Path> named(String reference, Path file, Path artifact, Set<String> ruleNames)
	{
		for (String root : PLUGIN_ROOTS)
		{
			if (reference.startsWith(root + "/"))
				return Optional.of(artifact.resolve(reference.substring(root.length() + 1)).normalize());
		}
		return relative(reference, file).filter(path -> ruleNames.contains(path.getFileName().toString()));
	}

	/**
	 * Resolves a relative reference against the directory of the file that holds it.
	 *
	 * @param reference the reference
	 * @param file      the file that holds the reference
	 * @return the named path, or empty if the reference is not a relative path, such as one through the plugin
	 *   directory or a placeholder
	 */
	private static Optional<Path> relative(String reference, Path file)
	{
		if (reference.startsWith("$") || reference.contains("<"))
			return Optional.empty();
		Path path;
		try
		{
			path = Path.of(reference);
		}
		catch (InvalidPathException _)
		{
			return Optional.empty();
		}
		if (path.isAbsolute())
			return Optional.empty();
		return Optional.of(file.getParent().resolve(path).normalize());
	}

	/**
	 * Returns whether a path is a regular Markdown file.
	 *
	 * @param path the path
	 * @return {@code true} if the path is a regular file whose name ends in {@code .md}
	 */
	private static boolean isMarkdown(Path path)
	{
		return Files.isRegularFile(path, NOFOLLOW_LINKS) && path.getFileName().toString().endsWith(".md");
	}

	/**
	 * Joins the names of a relative path with {@code /} on every platform.
	 *
	 * @param path the relative path
	 * @return the slash-separated path
	 */
	private static String slashSeparated(Path path)
	{
		StringJoiner joiner = new StringJoiner("/");
		for (Path name : path)
			joiner.add(name.toString());
		return joiner.toString();
	}
}
