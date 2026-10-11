package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Arrays;
import java.util.regex.PatternSyntaxException;

/**
 * A pattern of a prompt test's {@code ## Fixtures} section, which selects the files copied into the workspace.
 * <p>
 * The pattern is relative to the prompt test's directory and has the form {@code <directory>/fixtures/<glob>}. Its
 * base is that {@code fixtures} directory, and a file's workspace path is its path below the base. The glob follows
 * the syntax of {@link FileSystems#getDefault()}'s {@code glob:} matcher, in which {@code \} escapes a character.
 *
 * @param text   the pattern as written
 * @param prefix the part up to and including the {@code fixtures} directory, such as {@code ../fixtures}
 * @param glob   the part after it, which selects workspace paths
 */
public record FixturePattern(String text, String prefix, String glob)
{
	/**
	 * The pattern that applies to a prompt test without a {@code ## Fixtures} section: every file of its own
	 * {@code fixtures} directory.
	 */
	public static final String DEFAULT = "fixtures/**";
	/**
	 * The directory name that ends a pattern's base.
	 */
	private static final String FIXTURES = "fixtures";

	/**
	 * Creates a pattern.
	 *
	 * @param text   the pattern as written
	 * @param prefix the part up to and including the {@code fixtures} directory, such as {@code ../fixtures}
	 * @param glob   the part after it, which selects workspace paths
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code prefix} or {@code glob} are blank
	 */
	public FixturePattern
	{
		requireThat(text, "text").isNotNull();
		requireThat(prefix, "prefix").isNotBlank();
		requireThat(glob, "glob").isNotBlank();
	}

	/**
	 * Parses a pattern.
	 *
	 * @param text the pattern
	 * @return the pattern
	 * @throws NullPointerException     if {@code text} is null
	 * @throws IllegalArgumentException if {@code text} is absolute, has no {@code fixtures} directory, has a glob
	 *                                  character before it, has nothing after it, or its glob is malformed
	 */
	public static FixturePattern parse(String text)
	{
		requireThat(text, "text").isNotNull();
		if (text.startsWith("/"))
		{
			throw new IllegalArgumentException("The pattern " + text + " is absolute; write it relative to the " +
				"prompt test's directory, such as ../fixtures/**");
		}
		String[] segments = text.split("/", -1);
		int fixtures = -1;
		for (int i = 0; i < segments.length; ++i)
		{
			if (segments[i].equals(FIXTURES))
			{
				fixtures = i;
				break;
			}
			if (hasGlob(segments[i]))
			{
				throw new IllegalArgumentException("The pattern " + text + " has a glob before its fixtures " +
					"directory; only the part after fixtures/ may be a glob");
			}
		}
		if (fixtures == -1)
		{
			throw new IllegalArgumentException("The pattern " + text + " has no fixtures directory; write it as " +
				"<directory>/fixtures/<glob>, such as ../fixtures/**");
		}
		String prefix = String.join("/", Arrays.copyOfRange(segments, 0, fixtures + 1));
		String glob = String.join("/", Arrays.copyOfRange(segments, fixtures + 1, segments.length));
		if (glob.isBlank())
		{
			throw new IllegalArgumentException("The pattern " + text + " selects no file below its fixtures " +
				"directory; add a glob, such as fixtures/**");
		}
		try
		{
			FileSystems.getDefault().getPathMatcher("glob:" + glob);
		}
		catch (PatternSyntaxException e)
		{
			throw new IllegalArgumentException("The pattern " + text + " is not a valid glob: " + e.getDescription(), e);
		}
		return new FixturePattern(text, prefix, glob);
	}

	/**
	 * Indicates whether a path segment holds a glob character.
	 *
	 * @param segment the segment
	 * @return {@code true} if it holds {@code *}, {@code ?}, {@code [}, <code>{</code> or {@code \}
	 */
	private static boolean hasGlob(String segment)
	{
		for (char character : segment.toCharArray())
		{
			if ("*?[{\\".indexOf(character) != -1)
				return true;
		}
		return false;
	}

	/**
	 * Returns a pattern that selects exactly one workspace path of a prompt test's own {@code fixtures} directory.
	 *
	 * @param path the workspace path
	 * @return {@code fixtures/} followed by {@code path} with every glob character escaped
	 * @throws NullPointerException if {@code path} is null
	 */
	public static String exact(String path)
	{
		requireThat(path, "path").isNotNull();
		StringBuilder escaped = new StringBuilder(FIXTURES.length() + 1 + path.length());
		escaped.append(FIXTURES).append('/');
		for (char character : path.toCharArray())
		{
			if ("\\*?[]{}".indexOf(character) != -1)
				escaped.append('\\');
			escaped.append(character);
		}
		return escaped.toString();
	}

	/**
	 * Returns the directory that holds the files this pattern selects.
	 *
	 * @param testDirectory the prompt test's directory
	 * @return the {@code fixtures} directory that the pattern names, normalized
	 * @throws NullPointerException if {@code testDirectory} is null
	 */
	public Path base(Path testDirectory)
	{
		requireThat(testDirectory, "testDirectory").isNotNull();
		return testDirectory.resolve(prefix).normalize();
	}

	/**
	 * Indicates whether this pattern selects a workspace path.
	 *
	 * @param path a path below the base, with {@code /} separators
	 * @return {@code true} if the glob matches {@code path}
	 * @throws NullPointerException if {@code path} is null
	 */
	public boolean matches(String path)
	{
		requireThat(path, "path").isNotNull();
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
		return matcher.matches(Path.of(path));
	}
}
