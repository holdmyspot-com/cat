package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runs retained-output quality gates in isolated Git worktrees.
 */
public final class OutputGates
{
	private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
	private enum TreeSource
	{
		RETAINED,
		DRAFT
	}

	private final Git git;
	private final DirectoryCreator directories;

	/**
	 * Creates the output-gate executor.
	 *
	 * @param git the repository Git boundary
	 * @param directories the manifest-local temporary-directory owner
	 */
	public OutputGates(Git git, DirectoryCreator directories)
	{
		requireThat(git, "git").isNotNull();
		requireThat(directories, "directories").isNotNull();
		this.git = git;
		this.directories = directories;
	}

	/**
	 * Runs one command against every selected sealed output tree.
	 *
	 * @param manifestFile the manifest that owns gate logs and temporary worktrees
	 * @param gateName the stable gate-log prefix
	 * @param targets the selected output trees and their commands
	 * @throws IOException if a target is invalid, cannot be materialized, or its command fails
	 */
	public void run(Path manifestFile, String gateName, List<Target> targets) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(gateName, "gateName").isNotBlank();
		requireThat(targets, "targets").isNotEmpty();
		if (!Gates.isName(gateName))
			throw new IOException("Topic-replay gate names must use lowercase letters, digits, and hyphens: " + gateName);

		Path outputDirectory = manifestFile.toAbsolutePath().normalize().getParent().resolve("gates");
		Files.createDirectories(outputDirectory);
		for (Target target : targets)
			run(manifestFile, outputDirectory, gateName, target, TreeSource.RETAINED);
	}

	/**
	 * Runs one command against a proposed output tree before its plan is sealed.
	 *
	 * @param manifestFile the manifest that owns gate logs and the temporary worktree
	 * @param gateName the stable gate-log prefix
	 * @param target the proposed output tree and its acceptance command
	 * @throws IOException if the target is invalid, cannot be materialized, or its command fails
	 */
	public void runDraft(Path manifestFile, String gateName, Target target) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(gateName, "gateName").isNotBlank();
		requireThat(target, "target").isNotNull();
		if (!Gates.isName(gateName))
			throw new IOException("Topic-replay gate names must use lowercase letters, digits, and hyphens: " + gateName);

		Path outputDirectory = manifestFile.toAbsolutePath().normalize().getParent().resolve("gates");
		Files.createDirectories(outputDirectory);
		run(manifestFile, outputDirectory, gateName, target, TreeSource.DRAFT);
	}

	/**
	 * Materializes and checks one output tree, retaining its worktree when the command fails.
	 *
	 * @param manifestFile the manifest that owns the temporary worktree
	 * @param outputDirectory the directory that retains the gate log
	 * @param gateName the stable gate-log prefix
	 * @param target the selected output tree and command
	 * @param treeSource whether Git must resolve a retained tree or create a temporary draft commit
	 * @throws IOException if the output cannot be checked
	 */
	private void run(Path manifestFile, Path outputDirectory, String gateName, Target target, TreeSource treeSource)
		throws IOException
	{
		if (isTrivial(target.command()))
		{
			throw new IOException("Topic-replay output gates must exercise a build or behavior; a no-op command cannot " +
				"satisfy per-output evidence");
		}
		String progressPrefix = "outputGate=" + gateName + " output=" + target.outputId();
		System.out.println(progressPrefix + " status=started");
		Path worktree = directories.create(manifestFile, "git-topic-replay-output-");
		String commit = switch (treeSource)
		{
			case RETAINED -> commitForTree(target.tree());
			case DRAFT -> draftCommitForTree(target.tree());
		};
		git.lines("worktree", "add", "--detach", worktree.toString(), commit);
		Path outputFile = outputDirectory.resolve(gateName + "-" + target.outputId() + ".log");
		int exitCode = new CommandRunner(worktree).run(target.command(), outputFile, HEARTBEAT_INTERVAL,
			() -> System.out.println(progressPrefix + " status=running"));
		if (exitCode != 0)
		{
			throw new IOException("Topic-replay output gate " + gateName + " failed for " + target.outputId() +
				" with exit code " + exitCode + "; inspect " + outputFile + " and failing worktree " + worktree);
		}
		git.lines("worktree", "remove", worktree.toString());
		System.out.println(progressPrefix + " status=passed");
		System.out.println("gateLog=" + outputFile);
	}

	/**
	 * Creates a detached commit that materializes a proposed tree before the tree becomes reachable from {@code HEAD}.
	 *
	 * @param tree the proposed output tree
	 * @return the temporary commit that owns the tree
	 * @throws IOException if Git cannot create the temporary commit
	 */
	private String draftCommitForTree(String tree) throws IOException
	{
		return git.lines("commit-tree", tree, "-m", "topic-replay draft acceptance").getFirst();
	}

	/**
	 * Finds the rewritten commit that owns a sealed output tree.
	 *
	 * @param tree the sealed output tree
	 * @return the rewritten commit
	 * @throws IOException if no reachable rewritten commit owns the tree
	 */
	private String commitForTree(String tree) throws IOException
	{
		// rev-list prints a "commit <hash>" header line before each commit's formatted tree line
		List<String> commitsAndTrees = git.lines("rev-list", "--reverse", "--format=%T", "HEAD");
		for (int index = 0; index + 1 < commitsAndTrees.size(); index += 2)
		{
			if (commitsAndTrees.get(index + 1).equals(tree))
				return commitsAndTrees.get(index).substring("commit ".length());
		}
		throw new IOException("Topic-replay output tree is not reachable from HEAD: " + tree);
	}

	/**
	 * Indicates whether a command has no observable verification effect.
	 *
	 * @param command the candidate output-gate command
	 * @return {@code true} if the command is a common no-op form
	 */
	static boolean isTrivial(List<String> command)
	{
		if (command.size() == 1)
			return command.getFirst().equals("true") || command.getFirst().equals(":");
		if (command.size() == 2 && (command.getFirst().equals("/bin/true") || command.getFirst().equals("true")))
			return true;
		if (command.size() == 3 && (command.getFirst().equals("sh") || command.getFirst().equals("/bin/sh")) &&
			command.get(1).equals("-c"))
		{
			String body = command.get(2).trim();
			return body.equals("true") || body.equals(":") || body.equals("exit 0");
		}
		return false;
	}

	/**
	 * One selected output tree and the command that proves its behavior.
	 *
	 * @param outputId the logical output identifier
	 * @param tree the sealed output tree
	 * @param command the output command
	 */
	public record Target(String outputId, String tree, List<String> command)
	{
	}

	/**
	 * Creates a manifest-local directory that the caller retains when a gate fails.
	 */
	@FunctionalInterface
	public interface DirectoryCreator
	{
		/**
		 * Creates one temporary directory.
		 *
		 * @param manifestFile the manifest that owns the directory
		 * @param prefix the directory prefix
		 * @return the created directory
		 * @throws IOException if the directory cannot be created
		 */
		Path create(Path manifestFile, String prefix) throws IOException;
	}
}
