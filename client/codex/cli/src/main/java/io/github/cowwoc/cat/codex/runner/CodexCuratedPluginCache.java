package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.HarnessCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Seeds an isolated Codex home with the parent's verified curated-plugin snapshot.
 */
final class CodexCuratedPluginCache
{
	private static final int MAXIMUM_REVISION_BYTES = 128;
	private static final Pattern GIT_REVISION = Pattern.compile("[0-9a-f]{40}");

	/**
	 * Copies the parent snapshot into an isolated home when its recorded revision is usable.
	 *
	 * <p>
	 * Codex synchronizes its curated catalog when each fresh {@code CODEX_HOME} starts. The catalog itself is immutable
	 * at a recorded Git revision, so a child can start from the parent's verified snapshot and let Codex make its normal
	 * small remote revision check. The clone has its own working tree and Git metadata: sharing the parent's directory or
	 * hard-linking its files would let a child modify parent state and would violate runner isolation.
	 *
	 * @param parentHome the parent Codex home that may contain a curated snapshot
	 * @param isolatedHome the fresh child Codex home
	 * @param environment the isolated command environment
	 * @return {@code true} if an isolated snapshot was created, or {@code false} when ordinary Codex synchronization
	 *         must proceed
	 */
	static boolean seed(Path parentHome, Path isolatedHome, Map<String, String> environment)
	{
		requireThat(parentHome, "parentHome").isNotNull();
		requireThat(isolatedHome, "isolatedHome").isNotNull();
		requireThat(environment, "environment").isNotNull();
		Path parentTemporaryDirectory = parentHome.resolve(".tmp");
		Path parentRepository = parentTemporaryDirectory.resolve("plugins");
		Path parentRevision = parentTemporaryDirectory.resolve("plugins.sha");
		String revision;
		try
		{
			if (!Files.isDirectory(parentRepository.resolve(".git")) || !Files.isRegularFile(parentRevision))
				return false;
			revision = BoundedInput.readUtf8(parentRevision, MAXIMUM_REVISION_BYTES,
				"Codex curated-plugin revision").strip();
		}
		catch (IOException _)
		{
			return false;
		}
		if (!GIT_REVISION.matcher(revision).matches())
			return false;
		Path childTemporaryDirectory = isolatedHome.resolve(".tmp");
		Path childRepository = childTemporaryDirectory.resolve("plugins");
		try
		{
			Files.createDirectories(childTemporaryDirectory);
			HarnessCommand.execute(cloneCommand(parentRepository, childRepository), isolatedHome, environment);
			HarnessCommand.execute(checkoutCommand(revision), childRepository, environment);
			Files.writeString(childTemporaryDirectory.resolve("plugins.sha"), revision + System.lineSeparator());
			return true;
		}
		catch (IOException e)
		{
			try
			{
				TemporaryDirectories.deleteRecursively(childTemporaryDirectory);
			}
			catch (IOException cleanupFailure)
			{
				e.addSuppressed(cleanupFailure);
			}
			return false;
		}
	}

	/**
	 * Returns the command that gives the child its own working tree and metadata over the parent's immutable objects.
	 *
	 * @param parentRepository the parent's curated-plugin repository
	 * @param childRepository the child's repository, which must not exist yet
	 * @return the clone command
	 */
	static List<String> cloneCommand(Path parentRepository, Path childRepository)
	{
		return List.of("git", "clone", "--quiet", "--shared", "--no-checkout", parentRepository.toString(),
			childRepository.toString());
	}

	/**
	 * Returns the command that checks out the parent's verified revision in the child's repository.
	 *
	 * @param revision the verified revision
	 * @return the checkout command
	 */
	static List<String> checkoutCommand(String revision)
	{
		return List.of("git", "checkout", "--quiet", "--detach", revision);
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private CodexCuratedPluginCache()
	{
	}
}
