package io.github.cowwoc.cat.distribution.bundle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.BoundedInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Preserves the file selected by an inline Markdown reference when rule routing moves its source or target.
 */
final class RuleReferences
{
	private static final Pattern MARKDOWN_REFERENCE = Pattern.compile("`([^`\\r\\n]+\\.md)`");

	/**
	 * Prevents instantiation.
	 */
	private RuleReferences()
	{
	}

	/**
	 * Rewrites inline Markdown file references affected by relocated rules.
	 *
	 * @param rules the rules root
	 * @param relocations original to relocated rule paths
	 * @throws IOException if a Markdown rule cannot be read or written
	 */
	static void relocate(Path rules, Map<Path, Path> relocations) throws IOException
	{
		Path normalizedRules = rules.toAbsolutePath().normalize();
		Map<Path, Path> normalized = relocations.entrySet().stream().collect(Collectors.toMap(
			entry -> entry.getKey().toAbsolutePath().normalize(),
			entry -> entry.getValue().toAbsolutePath().normalize()));
		if (normalized.isEmpty())
			return;
		try (Stream<Path> files = Files.walk(normalizedRules))
		{
			for (Path file : files.filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS) &&
				path.getFileName().toString().endsWith(".md")).toList())
			{
				Path current = file.toAbsolutePath().normalize();
				Path original = originalPath(current, normalized);
				String content = BoundedInput.readUtf8(file, RuleArtifactRouter.MAXIMUM_RULE_INPUT_BYTES, "Rule");
				Matcher matcher = MARKDOWN_REFERENCE.matcher(content);
				StringBuffer rewritten = new StringBuffer(content.length());
				while (matcher.find())
				{
					String reference = matcher.group(1);
					if (reference.startsWith("/") || reference.contains("${"))
					continue;
					Path originalTarget = original.getParent().resolve(reference).normalize();
					Path target = normalized.getOrDefault(originalTarget, originalTarget);
					if (!target.startsWith(normalizedRules) || !Files.isRegularFile(target, NOFOLLOW_LINKS) ||
						(original.equals(current) && target.equals(originalTarget)))
						continue;
					String replacement = current.getParent().relativize(target).toString().replace('\\', '/');
					matcher.appendReplacement(rewritten, Matcher.quoteReplacement("`" + replacement + "`"));
				}
				matcher.appendTail(rewritten);
				if (!content.equals(rewritten.toString()))
					Files.writeString(file, rewritten, UTF_8);
			}
		}
	}

	/**
	 * Finds the source path for a currently relocated file.
	 *
	 * @param current the current file path
	 * @param relocations original to relocated paths
	 * @return the original path, or {@code current} when it was not moved
	 */
	private static Path originalPath(Path current, Map<Path, Path> relocations)
	{
		for (Map.Entry<Path, Path> entry : relocations.entrySet())
			if (entry.getValue().equals(current))
				return entry.getKey();
		return current;
	}
}
