package com.holdmyspot.cat.common.fakes.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.git.Git;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates Git work trees for tests whose outcome depends on what Git ignores.
 */
public final class GitWorkTrees
{
	/**
	 * Prevents instantiation.
	 */
	private GitWorkTrees()
	{
	}

	/**
	 * Creates a Git work tree that ignores nothing: it has no ignore file, and its user-wide exclude file is empty, so
	 * the user's own Git configuration does not change the outcome.
	 *
	 * @param project the work tree's directory, which is created if absent
	 * @return {@code project}
	 * @throws IOException if the repository cannot be created
	 */
	public static Path create(Path project) throws IOException
	{
		Files.createDirectories(project);
		Git git = new Git(project);
		git.run("init", "--quiet");
		Path noExcludes = Files.writeString(project.resolve(".git/no-excludes"), "", UTF_8);
		git.run("config", "core.excludesFile", noExcludes.toString());
		git.run("config", "user.name", "Test");
		git.run("config", "user.email", "test@example.com");
		git.run("config", "commit.gpgsign", "false");
		return project;
	}
}
