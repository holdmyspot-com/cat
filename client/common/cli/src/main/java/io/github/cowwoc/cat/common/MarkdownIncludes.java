package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Parses CAT Markdown include directives and resolves their targets within declared source roots.
 */
public final class MarkdownIncludes
{
	/**
	 * The name that a rule's include target writes for the folder of the current platform's fragments:
	 * {@code posix} or {@code windows}. The rule loader replaces it, so that a rule written once for every platform
	 * includes only the text that differs between them.
	 */
	public static final String PLATFORM_PLACEHOLDER = "{platform}";
	/**
	 * Matches one CAT Markdown include directive, a line that consists of the directive alone, and captures its
	 * relative target path, which may name the {@link #PLATFORM_PLACEHOLDER}. Text that mentions the directive within
	 * a longer line is not a directive.
	 */
	public static final Pattern DIRECTIVE = Pattern.compile("^<!-- cat:include ((?:[A-Za-z0-9_./-]|" +
		Pattern.quote(PLATFORM_PLACEHOLDER) + ")+) -->$", Pattern.MULTILINE);

	/**
	 * Resolves one relative include target without allowing a symbolic link or a path outside the supplied source roots.
	 *
	 * @param source the file that declares the include
	 * @param target the relative included-file path
	 * @param description the reader-facing kind of include for an error message
	 * @param allowedRoots the source roots that may contain the target
	 * @return the regular included file
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code target}, {@code description}, or {@code allowedRoots} is empty
	 * @throws IOException if the target is unavailable, symbolic, or outside every allowed root
	 */
	public static Path resolve(Path source, String target, String description, Path... allowedRoots) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(target, "target").isNotBlank();
		requireThat(description, "description").isNotBlank();
		requireThat(allowedRoots, "allowedRoots").isNotEmpty();
		Path included = source.getParent().resolve(target).normalize();
		boolean allowed = false;
		for (Path root : allowedRoots)
		{
			requireThat(root, "allowedRoot").isNotNull();
			if (included.startsWith(root.normalize()))
			{
				allowed = true;
				break;
			}
		}
		if (!allowed || Files.isSymbolicLink(included) || !Files.isRegularFile(included, NOFOLLOW_LINKS))
		{
			throw new IOException(description + " must name a regular file inside its allowed source root: " + source);
		}
		return included;
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private MarkdownIncludes()
	{
	}
}
