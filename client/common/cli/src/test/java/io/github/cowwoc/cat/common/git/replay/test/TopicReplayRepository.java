package io.github.cowwoc.cat.common.git.replay.test;

import io.github.cowwoc.cat.common.git.Git;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

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
		Path root = repository.resolve(".cat/work/temp");
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
	 * Commits successive versions of one file on the current branch in one Git process.
	 * <p>
	 * The work tree and index keep their earlier contents.
	 *
	 * @param git Git access for the repository
	 * @param repository the fixture repository
	 * @param path the repository-relative file path
	 * @param versions the file's content in each new commit, in commit order
	 * @return the created commit hashes, in commit order
	 * @throws IOException if Git cannot create the commits
	 */
	static List<String> commitVersions(Git git, Path repository, String path, List<String> versions)
		throws IOException
	{
		String branch = git.lines("symbolic-ref", "HEAD").getFirst();
		List<String> commands = new ArrayList<>(versions.size() * 2);
		for (int version = 1; version <= versions.size(); ++version)
		{
			String parent;
			if (version == 1)
				parent = branch + "^0";
			else
				parent = ":" + (version - 1);
			commands.add("commit " + branch + "\nmark :" + version +
				"\ncommitter CAT Test <cat-test@example.invalid> 0 +0000\n" + data("test: version " + version) +
				"from " + parent + "\nM 100644 inline " + path + "\n" + data(versions.get(version - 1)));
		}
		for (int version = 1; version <= versions.size(); ++version)
			commands.add("get-mark :" + version + "\n");
		return fastImport(repository, String.join("", commands));
	}

	/**
	 * Returns a fast-import {@code data} command that holds a value.
	 *
	 * @param value the exact bytes to store, as UTF-8 text
	 * @return the command, including its byte count and a trailing line break
	 */
	private static String data(String value)
	{
		return "data " + value.getBytes(UTF_8).length + "\n" + value + "\n";
	}

	/**
	 * Runs {@code git fast-import} on a command stream and returns the lines that its {@code get-mark} and {@code ls}
	 * commands print.
	 * <p>
	 * This creates many objects and references in one Git process, where creating them through the work tree would start
	 * several processes for each commit.
	 *
	 * @param repository the repository that receives the objects and references
	 * @param stream the fast-import commands
	 * @return the printed lines, in command order
	 * @throws IOException if Git cannot run or rejects the stream
	 */
	static List<String> fastImport(Path repository, String stream) throws IOException
	{
		ProcessBuilder builder = new ProcessBuilder("git", "fast-import", "--quiet").directory(repository.toFile()).
			redirectErrorStream(true);
		try (Process process = builder.start())
		{
			// The printed lines are far smaller than a pipe's buffer, so Git cannot block before the stream is closed
			try (OutputStream input = process.getOutputStream())
			{
				input.write(stream.getBytes(UTF_8));
			}
			String output = new String(process.getInputStream().readAllBytes(), UTF_8);
			if (process.waitFor() != 0)
				throw new IOException("git fast-import failed: " + output);
			return output.lines().toList();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for git fast-import", e);
		}
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
