package com.holdmyspot.cat.common.fakes.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.git.Git;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates Git work trees for tests whose outcome depends on what Git ignores.
 * <p>
 * {@link #create(Path)} runs {@code git init}. {@link #ignoring(Path)} and {@link #notIgnoring(Path)} write the
 * files that {@code git init --quiet} writes and Git needs to recognize a work tree, which is much faster, and leave
 * every question about the work tree to real Git.
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

	/**
	 * Creates a Git work tree whose Git ignores the CAT session directories, through the work tree's own exclude file,
	 * so that the project's directory gains no {@code .gitignore}.
	 *
	 * @param project the work tree's directory, which is created if absent
	 * @return {@code project}
	 * @throws IOException if the work tree cannot be created
	 */
	public static Path ignoring(Path project) throws IOException
	{
		Path git = skeleton(project);
		Files.writeString(git.resolve("info/exclude"), SessionIgnore.ENTRY + "\n", UTF_8);
		return project;
	}

	/**
	 * Creates a Git work tree that ignores nothing, as {@link #create(Path)} does.
	 *
	 * @param project the work tree's directory, which is created if absent
	 * @return {@code project}
	 * @throws IOException if the work tree cannot be created
	 */
	public static Path notIgnoring(Path project) throws IOException
	{
		Path git = skeleton(project);
		Path noExcludes = Files.writeString(git.resolve("no-excludes"), "", UTF_8);
		Files.writeString(git.resolve("config"), "[core]\n\texcludesFile = " + noExcludes + "\n", UTF_8);
		return project;
	}

	/**
	 * Writes the files that make a directory a Git work tree with no commits and an empty exclude file.
	 *
	 * @param project the work tree's directory, which is created if absent
	 * @return the work tree's {@code .git} directory
	 * @throws IOException if the files cannot be written
	 */
	private static Path skeleton(Path project) throws IOException
	{
		Path git = Files.createDirectories(project).resolve(".git");
		Files.createDirectories(git.resolve("objects"));
		Files.createDirectories(git.resolve("refs"));
		Files.createDirectories(git.resolve("info"));
		Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n", UTF_8);
		return git;
	}
}
