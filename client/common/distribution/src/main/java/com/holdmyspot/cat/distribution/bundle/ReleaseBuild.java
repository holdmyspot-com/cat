package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Finds the release commit that a build names in its plugin version: the commit that the release workflow names
 * through the Maven property {@value #PROPERTY}, or else the {@code HEAD} of the project's Git working tree.
 */
public final class ReleaseBuild
{
	/**
	 * The Maven property through which the release workflow names the release commit.
	 */
	public static final String PROPERTY = "cat.build";
	/**
	 * The number of hexadecimal digits of the release commit that a plugin version holds.
	 */
	private static final int DIGITS = 12;
	/**
	 * The only spelling of a release commit in a plugin version.
	 */
	private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{" + DIGITS + "}");
	/**
	 * The most output that {@code git rev-parse} may write: one object name, its line terminator and room for an
	 * error message.
	 */
	private static final int MAXIMUM_OUTPUT_BYTES = 4096;

	/**
	 * Returns the release commit's first twelve lower-case hexadecimal digits.
	 *
	 * @param named       the value of {@value #PROPERTY}, or null or empty when the build names no commit
	 * @param projectRoot the root of the project's Git working tree, whose {@code HEAD} is used when the build names no
	 *                    commit; Git does not look for a repository above it
	 * @return the commit's first twelve lower-case hexadecimal digits
	 * @throws NullPointerException if {@code projectRoot} is null
	 * @throws IOException          if {@code named} is not twelve lower-case hexadecimal digits, or the build names no
	 *                              commit and Git cannot read the {@code HEAD} of {@code projectRoot}
	 */
	public static String commit(String named, Path projectRoot) throws IOException
	{
		return commit(named, projectRoot, ReleaseBuild::runRevParse);
	}

	/**
	 * Returns the release commit's first twelve lower-case hexadecimal digits, as {@link #commit(String, Path)} does,
	 * deciding from the answer that a given Git gives.
	 *
	 * @param named       the value of {@value #PROPERTY}, or null or empty when the build names no commit
	 * @param projectRoot the root of the project's Git working tree, whose {@code HEAD} is used when the build names no
	 *                    commit
	 * @param git         answers what {@code git rev-parse --verify --quiet HEAD} reports in {@code projectRoot}
	 * @return the commit's first twelve lower-case hexadecimal digits
	 * @throws NullPointerException if any argument other than {@code named} is null
	 * @throws IOException          if {@code named} is not twelve lower-case hexadecimal digits, or the build names no
	 *                              commit and Git cannot read the {@code HEAD} of {@code projectRoot}
	 */
	public static String commit(String named, Path projectRoot, GitHead git) throws IOException
	{
		requireThat(projectRoot, "projectRoot").isNotNull();
		requireThat(git, "git").isNotNull();
		if (named != null && !named.isEmpty())
		{
			if (!COMMIT.matcher(named).matches())
			{
				throw new IOException("-D" + PROPERTY + " must be the first " + DIGITS + " lower-case hexadecimal " +
					"digits of the release commit: \"" + named + "\"");
			}
			return named;
		}
		String head = head(projectRoot.toAbsolutePath().normalize(), git);
		if (head.length() < DIGITS || !COMMIT.matcher(head.substring(0, DIGITS)).matches())
			throw new IOException("Git named an unexpected HEAD of " + projectRoot + ": \"" + head + "\"");
		return head.substring(0, DIGITS);
	}

	/**
	 * Reads the object name of a working tree's {@code HEAD}.
	 *
	 * @param projectRoot the working tree's root, an absolute normalized path
	 * @param git         answers what Git reports for the working tree's {@code HEAD}
	 * @return the object name
	 * @throws IOException if Git cannot run or cannot read the {@code HEAD}
	 */
	private static String head(Path projectRoot, GitHead git) throws IOException
	{
		GitAnswer answer = git.revParseHead(projectRoot);
		if (answer.status() != 0)
		{
			throw new IOException(noHead(projectRoot, "git rev-parse exited with status " + answer.status() + ": " +
				answer.output()));
		}
		return answer.output();
	}

	/**
	 * Runs {@code git rev-parse --verify --quiet HEAD} in a working tree.
	 *
	 * @param projectRoot the working tree's root, an absolute normalized path
	 * @return what Git reported
	 * @throws IOException if Git cannot run
	 */
	private static GitAnswer runRevParse(Path projectRoot) throws IOException
	{
		ProcessBuilder builder = new ProcessBuilder(List.of("git", "-C", projectRoot.toString(), "rev-parse",
			"--verify", "--quiet", "HEAD")).redirectErrorStream(true);
		Path parent = projectRoot.getParent();
		// A project that is no working tree of its own must not use the HEAD of a repository that holds it
		if (parent != null)
			builder.environment().put("GIT_CEILING_DIRECTORIES", parent.toString());
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			String output = new String(process.getInputStream().readNBytes(MAXIMUM_OUTPUT_BYTES), UTF_8).strip();
			process.getInputStream().transferTo(OutputStream.nullOutputStream());
			return new GitAnswer(process.waitFor(), output);
		}
		catch (IOException e)
		{
			throw new IOException(noHead(projectRoot, "Git cannot run: " + e.getMessage()), e);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException(noHead(projectRoot, "the build was interrupted while Git ran"), e);
		}
	}

	/**
	 * Returns the message of a build that cannot find its release commit.
	 *
	 * @param projectRoot the project's root
	 * @param reason      why the commit was not found
	 * @return the message
	 */
	private static String noHead(Path projectRoot, String reason)
	{
		return "Cannot find the release commit: the build names none through -D" + PROPERTY + "=<the commit's first " +
			DIGITS + " hexadecimal digits>, and the HEAD of " + projectRoot + " is unavailable because " + reason;
	}

	/**
	 * Prevents instantiation.
	 */
	private ReleaseBuild()
	{
	}

	/**
	 * Answers what {@code git rev-parse --verify --quiet HEAD} reports in a working tree.
	 */
	@FunctionalInterface
	public interface GitHead
	{
		/**
		 * Returns what {@code git rev-parse --verify --quiet HEAD} reports in a working tree: the object name of its
		 * {@code HEAD} and status 0, or a failure status and Git's message.
		 *
		 * @param projectRoot the working tree's root, an absolute normalized path
		 * @return what Git reported
		 * @throws IOException if Git cannot run; the message says why the release commit cannot be found
		 */
		GitAnswer revParseHead(Path projectRoot) throws IOException;
	}

	/**
	 * What a Git command reported.
	 *
	 * @param status the command's exit status
	 * @param output the command's output with its surrounding whitespace removed
	 */
	public record GitAnswer(int status, String output)
	{
		/**
		 * Creates an answer.
		 *
		 * @param status the command's exit status
		 * @param output the command's output with its surrounding whitespace removed
		 * @throws NullPointerException if {@code output} is null
		 */
		public GitAnswer
		{
			requireThat(output, "output").isNotNull();
		}
	}
}
