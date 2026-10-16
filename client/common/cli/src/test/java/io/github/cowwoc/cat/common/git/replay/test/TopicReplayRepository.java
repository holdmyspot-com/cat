package io.github.cowwoc.cat.common.git.replay.test;

import io.github.cowwoc.cat.common.git.Git;
import io.github.cowwoc.cat.common.git.replay.GitTopicReplayEditor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Creates isolated Git repositories and CAT-work invocation paths for topic-replay tests.
 */
final class TopicReplayRepository
{
	/**
	 * Prevents construction of static fixture operations.
	 */
	private TopicReplayRepository()
	{
	}

	/**
	 * Creates one named workflow directory beneath a repository's CAT-work temporary root.
	 *
	 * @param repository the fixture repository
	 * @param name the directory name
	 * @return the created invocation directory
	 * @throws IOException if the directory cannot be created
	 */
	static Path invocationDirectory(Path repository, String name) throws IOException
	{
		Files.createDirectories(repository.resolve(".cat"));
		Path root = GitTopicReplayEditor.temporaryRoot(repository);
		Files.createDirectories(root);
		return Files.createDirectory(root.resolve(name));
	}

	/**
	 * Returns the standard invocation manifest path for one fixture repository.
	 *
	 * @param repository the fixture repository
	 * @return the initially absent manifest path
	 * @throws IOException if the invocation directory cannot be created
	 */
	static Path invocationManifest(Path repository) throws IOException
	{
		return invocationDirectory(repository, "replay").resolve("manifest.json");
	}

	/**
	 * Writes and commits one file in the fixture repository.
	 *
	 * @param git Git access for the repository
	 * @param repository the fixture repository
	 * @param path the repository-relative file path
	 * @param content the file content
	 * @param summary the concise description of the fixture change
	 * @return the created commit hash
	 * @throws IOException if Git cannot commit the fixture file
	 */
	static String commit(Git git, Path repository, String path, String content, String summary) throws IOException
	{
		Path file = repository.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		git.run("add", path);
		git.run("commit", "--quiet", "--message", "test: " + summary);
		return git.run("rev-parse", "HEAD").trim();
	}

	/**
	 * Initializes an isolated Git repository for one replay test.
	 *
	 * @param repository the repository directory
	 * @throws IOException if Git cannot initialize the fixture
	 */
	static void initializeRepository(Path repository) throws IOException
	{
		Git git = new Git(repository);
		git.run("init", "--quiet");
		// Git merges a repeated section with the one that "git init" wrote, so appending sets the values without
		// starting Git once per value
		Files.writeString(repository.resolve(".git/config"), """
			[core]
				autocrlf = false
			[user]
				name = CAT Test
				email = cat-test@example.invalid
			""", StandardOpenOption.APPEND);
		git.run("commit", "--allow-empty", "--quiet", "--message", "base");
	}
}
