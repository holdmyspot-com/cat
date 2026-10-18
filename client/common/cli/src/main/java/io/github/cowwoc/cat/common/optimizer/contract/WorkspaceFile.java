package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A text file or link that a prompt test places in each trial's workspace: a file that the contract author wrote, or
 * a file or link of a recorded scenario that the test cites, which CAT copies from the scenario's file.
 *
 * @param path    the path relative to the workspace root, with {@code /} separators, which may contain {@code {{name}}}
 *                placeholders
 * @param content the UTF-8 content of a file, or the target of a link relative to the link's directory with {@code /}
 *                separators; either may contain {@code {{name}}} placeholders
 * @param source  the recorded file or link that CAT copied the content from, relative to the project when it lies
 *                inside it, or empty if the contract author wrote the workspace file
 * @param link    {@code true} if the workspace file is a link to another entry of the workspace
 */
public record WorkspaceFile(String path, String content, String source, boolean link)
{
	/**
	 * Creates a workspace file that the contract author wrote.
	 *
	 * @param path    the path relative to the workspace root, with {@code /} separators, which may contain
	 *                {@code {{name}}} placeholders
	 * @param content the UTF-8 content, which may contain {@code {{name}}} placeholders
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code path} is blank, starts or ends with {@code /}, contains {@code \},
	 *                                  has an empty, {@code .} or {@code ..} segment, or lies in the workspace's
	 *                                  {@code .git} directory
	 */
	public WorkspaceFile(String path, String content)
	{
		this(path, content, "", false);
	}

	/**
	 * Creates a workspace file that is not a link.
	 *
	 * @param path    the path relative to the workspace root, with {@code /} separators, which may contain
	 *                {@code {{name}}} placeholders
	 * @param content the UTF-8 content, which may contain {@code {{name}}} placeholders
	 * @param source  the recorded file that CAT copied the content from, relative to the project when it lies inside it,
	 *                or empty if the contract author wrote the workspace file
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code path} is blank, starts or ends with {@code /}, contains {@code \},
	 *                                  has an empty, {@code .} or {@code ..} segment, or lies in the workspace's
	 *                                  {@code .git} directory
	 */
	public WorkspaceFile(String path, String content, String source)
	{
		this(path, content, source, false);
	}

	/**
	 * Creates a workspace file or link.
	 *
	 * @param path    the path relative to the workspace root, with {@code /} separators, which may contain
	 *                {@code {{name}}} placeholders
	 * @param content the UTF-8 content of a file, or the target of a link relative to the link's directory with {@code /}
	 *                separators; either may contain {@code {{name}}} placeholders
	 * @param source  the recorded file or link that CAT copied the content from, relative to the project when it lies
	 *                inside it, or empty if the contract author wrote the workspace file
	 * @param link    {@code true} if the workspace file is a link to another entry of the workspace
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code path} is blank, starts or ends with {@code /}, contains {@code \},
	 *                                  has an empty, {@code .} or {@code ..} segment, or lies in the workspace's
	 *                                  {@code .git} directory, or a link's target contains {@code \}, has an empty
	 *                                  or {@code .} segment, or does not name an entry of the workspace outside its
	 *                                  {@code .git} directory
	 */
	public WorkspaceFile
	{
		requireThat(path, "path").isNotBlank();
		requireThat(content, "content").isNotNull();
		requireThat(source, "source").isNotNull();
		if (path.indexOf('\\') != -1)
		{
			throw new IllegalArgumentException("The workspace file path '" + path + "' contains '\\'. Separate directories " +
				"with '/'.");
		}
		for (String segment : path.split("/", -1))
		{
			if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
			{
				throw new IllegalArgumentException("The workspace file path '" + path + "' must be relative to the workspace " +
					"root, with no empty, '.' or '..' segment");
			}
		}
		if (path.equals(".git") || path.startsWith(".git/"))
		{
			throw new IllegalArgumentException("The workspace file path '" + path + "' lies in the workspace's .git " +
				"directory, which CAT creates");
		}
		if (link)
			requireLinkTarget(path, content);
	}

	/**
	 * Rejects a link's target that does not name an entry of the workspace outside its {@code .git} directory.
	 *
	 * @param path   the link's path relative to the workspace root, with {@code /} separators
	 * @param target the link's target relative to the link's directory
	 * @throws IllegalArgumentException if {@code target} contains {@code \}, has an empty or {@code .} segment, or
	 *                                  does not name an entry of the workspace outside its {@code .git} directory
	 */
	private static void requireLinkTarget(String path, String target)
	{
		if (target.indexOf('\\') != -1)
		{
			throw new IllegalArgumentException("The target '" + target + "' of the workspace link '" + path +
				"' contains '\\'. Separate directories with '/'.");
		}
		Deque<String> resolved = new ArrayDeque<>(List.of(path.split("/", -1)));
		resolved.removeLast();
		for (String segment : target.split("/", -1))
		{
			if (segment.isEmpty() || segment.equals("."))
			{
				throw new IllegalArgumentException("The target '" + target + "' of the workspace link '" + path +
					"' must be relative to the link's directory, with no empty or '.' segment");
			}
			if (!segment.equals(".."))
				resolved.addLast(segment);
			else if (resolved.pollLast() == null)
			{
				throw new IllegalArgumentException("The target '" + target + "' of the workspace link '" + path +
					"' lies outside the workspace");
			}
		}
		if (resolved.isEmpty() || resolved.getFirst().equals(".git"))
		{
			throw new IllegalArgumentException("The target '" + target + "' of the workspace link '" + path +
				"' is the workspace root or lies in its .git directory");
		}
	}
}
