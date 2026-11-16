package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.git.Git;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Makes the projects of runner tests Git repositories.
 */
final class TestRepositories
{
	/**
	 * Prevents instantiation.
	 */
	private TestRepositories()
	{
	}

	/**
	 * Makes a project a repository with one commit of its files, whose configuration owns every setting that a global
	 * configuration could change, and which ignores CAT's session directories.
	 *
	 * @param project    the project
	 * @param noExcludes an empty file that the repository names as its excludes file, outside the project
	 * @return the repository's Git
	 * @throws IOException if Git fails
	 */
	static Git initialize(Path project, Path noExcludes) throws IOException
	{
		Git git = new Git(project);
		git.run("init", "--quiet");
		// The sample hooks are disabled, so leaving them out only saves time
		TemporaryDirectories.deleteRecursively(project.resolve(".git/hooks"));
		Files.writeString(noExcludes, "");
		// A quoted configuration value escapes each backslash and double quote
		String excludes = noExcludes.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		Files.writeString(project.resolve(".git/config"), """
			[user]
				name = Test
				email = test@example.com
			[commit]
				gpgsign = false
			[core]
				excludesFile = "%s"
				autocrlf = false
			""".formatted(excludes), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		Files.writeString(project.resolve(".gitignore"), "/.cat/sessions/\n");
		git.run("add", "--all");
		git.run("commit", "--quiet", "-m", "initial");
		return git;
	}

	/**
	 * Returns how many worktrees a repository records, its main worktree included.
	 *
	 * @param git the repository's Git
	 * @return the count
	 * @throws IOException if Git fails
	 */
	static long worktreeCount(Git git) throws IOException
	{
		return git.lines("worktree", "list", "--porcelain").stream().filter(line -> line.startsWith("worktree ")).
			count();
	}
}
