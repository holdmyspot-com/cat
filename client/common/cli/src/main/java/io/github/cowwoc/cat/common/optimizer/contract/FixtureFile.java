package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A text file that a prompt test places in each trial's workspace: one that the contract author wrote, or one of a
 * recorded scenario that the test cites, which CAT copies from the scenario's file.
 *
 * @param path    the path relative to the workspace root, with {@code /} separators, which may contain
 *                {@code {{name}}} placeholders
 * @param content the UTF-8 content, which may contain {@code {{name}}} placeholders
 * @param source  the recorded file that CAT copied the content from, relative to the project when it lies inside it,
 *                or empty if the contract author wrote the fixture
 */
public record FixtureFile(String path, String content, String source)
{
	/**
	 * Creates a fixture file that the contract author wrote.
	 *
	 * @param path    the path relative to the workspace root, with {@code /} separators, which may contain
	 *                {@code {{name}}} placeholders
	 * @param content the UTF-8 content, which may contain {@code {{name}}} placeholders
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code path} is blank, starts or ends with {@code /}, contains {@code \},
	 *                                  has an empty, {@code .} or {@code ..} segment, or lies in the workspace's
	 *                                  {@code .git} directory
	 */
	public FixtureFile(String path, String content)
	{
		this(path, content, "");
	}

	/**
	 * Creates a fixture file.
	 *
	 * @param path    the path relative to the workspace root, with {@code /} separators, which may contain
	 *                {@code {{name}}} placeholders
	 * @param content the UTF-8 content, which may contain {@code {{name}}} placeholders
	 * @param source  the recorded file that CAT copied the content from, relative to the project when it lies inside
	 *                it, or empty if the contract author wrote the fixture
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code path} is blank, starts or ends with {@code /}, contains {@code \},
	 *                                  has an empty, {@code .} or {@code ..} segment, or lies in the workspace's
	 *                                  {@code .git} directory
	 */
	public FixtureFile
	{
		requireThat(path, "path").isNotBlank();
		requireThat(content, "content").isNotNull();
		requireThat(source, "source").isNotNull();
		if (path.indexOf('\\') != -1)
		{
			throw new IllegalArgumentException("The fixture path '" + path + "' contains '\\'. Separate directories " +
				"with '/'.");
		}
		for (String segment : path.split("/", -1))
		{
			if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
			{
				throw new IllegalArgumentException("The fixture path '" + path + "' must be relative to the workspace " +
					"root, with no empty, '.' or '..' segment");
			}
		}
		if (path.equals(".git") || path.startsWith(".git/"))
		{
			throw new IllegalArgumentException("The fixture path '" + path + "' lies in the workspace's .git " +
				"directory, which CAT creates");
		}
	}
}
