package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the Git history facts that define one topic-replay range.
 */
final class History
{
	static final String ROOT_BASE = "--root";
	static final String EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
	private final Path repository;
	private final Git git;

	/**
	 * Creates a history reader for one replay repository.
	 *
	 * @param repository the replay repository working directory
	 * @param git Git access for the replay repository
	 */
	History(Path repository, Git git)
	{
		this.repository = repository.toAbsolutePath().normalize();
		this.git = git;
	}

	/**
	 * Verifies that planning still refers to unchanged, clean source history.
	 *
	 * @param expectedTip the captured final source commit
	 * @throws IOException if Git is rebasing, the source tip changed, or tracked changes make planning evidence stale
	 */
	void verifyPlanningRepositoryState(String expectedTip) throws IOException
	{
		if (isInteractiveRebaseInProgress())
			throw new IOException("Topic-replay planning cannot run while Git is rebasing");
		String actualTip = git.lines("rev-parse", "HEAD").getFirst();
		if (!actualTip.equals(expectedTip))
		{
			throw new IOException("Topic-replay planning expected source tip " + expectedTip + " but found " + actualTip);
		}
		if (!git.lines("status", "--porcelain", "--untracked-files=no").isEmpty())
			throw new IOException("Topic-replay planning requires no tracked worktree or index changes");
	}

	/**
	 * Indicates whether Git is rebasing, so planning never captures mutable source history.
	 *
	 * @return {@code true} if Git has either supported rebase state directory
	 * @throws IOException if Git cannot locate its state directories
	 */
	private boolean isInteractiveRebaseInProgress() throws IOException
	{
		Path mergeState = repository.resolve(git.lines("rev-parse", "--git-path", "rebase-merge").getFirst()).
			normalize();
		Path applyState = repository.resolve(git.lines("rev-parse", "--git-path", "rebase-apply").getFirst()).
			normalize();
		return Files.isDirectory(mergeState) || Files.isDirectory(applyState);
	}

	/**
	 * Resolves a replay base while preserving the explicit orphan-root sentinel.
	 *
	 * @param base the supplied replay base
	 * @return the resolved commit hash or {@value #ROOT_BASE}
	 * @throws IOException if a non-root base cannot be resolved
	 */
	String resolveBase(String base) throws IOException
	{
		if (base.equals(ROOT_BASE))
			return ROOT_BASE;
		return git.lines("rev-parse", base).getFirst();
	}

	/**
	 * Returns the tree that precedes a replay range.
	 *
	 * @param base the resolved commit hash or {@value #ROOT_BASE}
	 * @return the preceding tree hash
	 * @throws IOException if a commit-backed base cannot be read
	 */
	String baseTree(String base) throws IOException
	{
		if (base.equals(ROOT_BASE))
			return EMPTY_TREE;
		return git.lines("rev-parse", base + "^{tree}").getFirst();
	}

	/**
	 * Returns one ordered replay range.
	 *
	 * @param base the resolved commit hash or {@value #ROOT_BASE}
	 * @param tip the range's final commit
	 * @return the ordered source commits
	 * @throws IOException if Git cannot read the range
	 */
	List<String> range(String base, String tip) throws IOException
	{
		if (base.equals(ROOT_BASE))
			return git.lines("rev-list", "--reverse", tip);
		return git.lines("rev-list", "--reverse", base + ".." + tip);
	}

	/**
	 * Returns the tree immediately before one source commit.
	 *
	 * @param source the source commit
	 * @return its parent tree, or the empty tree for an orphan root
	 * @throws IOException if Git cannot inspect the source commit
	 */
	String sourceBeforeTree(String source) throws IOException
	{
		List<String> parents = git.lines("rev-list", "--parents", "-n", "1", source);
		String[] values = parents.getFirst().split(" ");
		if (values.length == 1)
			return EMPTY_TREE;
		return git.lines("rev-parse", values[1] + "^{tree}").getFirst();
	}

	/**
	 * Returns immutable before-and-after path facts for one source commit.
	 *
	 * @param source the source commit
	 * @return one inventory entry for each path changed by the source
	 * @throws IOException if Git cannot inspect the source or one of its paths
	 */
	List<InventoryEntry> inventory(String source) throws IOException
	{
		List<String> paths = git.lines("diff-tree", "--root", "--no-commit-id", "--no-renames", "--name-only", "-r",
			source);
		List<String> parents = git.lines("rev-list", "--parents", "-n", "1", source);
		String[] parentFields = parents.getFirst().split(" ");
		String parent = null;
		if (parentFields.length > 1)
			parent = parentFields[1];
		List<InventoryEntry> result = new ArrayList<>(paths.size());
		for (int index = 0; index < paths.size(); ++index)
		{
			String path = paths.get(index);
			PathState before = PathState.absent();
			if (parent != null)
				before = pathState(parent, path);
			PathState after = pathState(source, path);
			result.add(new InventoryEntry(source + ":" + (index + 1), source, path, before.mode(), before.blob(),
				after.mode(), after.blob()));
		}
		return result;
	}

	/**
	 * Returns a path's Git tree state at one revision.
	 *
	 * @param revision the Git revision
	 * @param path the repository-relative path
	 * @return the path state, or an absent state when the path is not in the revision
	 * @throws IOException if Git cannot inspect the revision
	 */
	PathState pathState(String revision, String path) throws IOException
	{
		List<String> entries = git.lines("ls-tree", revision, "--", path);
		if (entries.isEmpty())
			return PathState.absent();
		String[] fields = entries.getFirst().split("\\s+", 4);
		if (fields.length < 3)
			throw new IOException("Git returned an invalid tree entry for " + path + " at " + revision);
		return new PathState(fields[0], fields[2]);
	}

	/**
	 * Derives the selectable product entry points changed by immutable inventory facts.
	 *
	 * @param inventory the source-path facts captured for one replay range
	 * @return one harness-neutral entry point for each affected product surface
	 * @throws IOException if a captured agent-manifest blob cannot be read
	 */
	List<ExposedEntryPoint> exposedEntryPoints(List<InventoryEntry> inventory) throws IOException
	{
		Map<String, Set<String>> pathsByEntryPoint = new HashMap<>();
		for (InventoryEntry item : inventory)
		{
			if (isCustomAgentManifest(item.path()))
			{
				for (String blob : List.of(item.beforeBlob(), item.afterBlob()))
				{
					if (blob.equals("0000000000000000000000000000000000000000"))
						continue;
					addExposedEntryPoint(pathsByEntryPoint, "agent:" + customAgentName(blob, item.path()), item.path());
				}
				continue;
			}
			String entryPoint = declaredEntryPoint(item.path());
			if (entryPoint != null)
				addExposedEntryPoint(pathsByEntryPoint, entryPoint, item.path());
		}
		return pathsByEntryPoint.entrySet().stream().map(entry -> new ExposedEntryPoint(entry.getKey(), "custom-agent",
			entry.getValue().stream().sorted().toList())).sorted(Comparator.comparing(ExposedEntryPoint::id)).toList();
	}

	/**
	 * Adds one immutable path to a logical entry point.
	 *
	 * @param pathsByEntryPoint paths indexed by logical entry point
	 * @param entryPoint the logical entry point
	 * @param path the changed path that exposes it
	 */
	private void addExposedEntryPoint(Map<String, Set<String>> pathsByEntryPoint, String entryPoint, String path)
	{
		pathsByEntryPoint.computeIfAbsent(entryPoint, ignored -> new HashSet<>()).add(path);
	}

	/**
	 * Returns the logical name of one non-agent product entry point.
	 *
	 * @param path a repository-relative changed path
	 * @return the entry point, or {@code null} when the path does not directly declare one
	 */
	private String declaredEntryPoint(String path)
	{
		String skillPrefix = "client/";
		if (path.matches("client/(codex|claude)/skills/src/main/resources/skills/[^/]+/SKILL\\.md"))
		{
			String[] segments = path.substring(skillPrefix.length()).split("/");
			return "skill:" + segments[6];
		}
		String installerPrefix = "client/lifecycle/src/main/resources/install/";
		if (path.matches(installerPrefix + "(bootstrap(-[a-z]+)?|install-[a-z]+|uninstall-[a-z]+)\\.sh"))
			return "installer:" + path.substring(installerPrefix.length(), path.length() - ".sh".length());
		if (path.equals("client/common/cli/src/main/java/io/github/cowwoc/cat/common/git/replay/GitTopicReplayEditor.java"))
			return "command:git-topic-replay";
		return null;
	}

	/**
	 * Returns whether a path declares a harness-specific custom agent.
	 *
	 * @param path a repository-relative path
	 * @return whether the path is a supported custom-agent manifest
	 */
	private boolean isCustomAgentManifest(String path)
	{
		return path.matches("client/(codex|claude)/plugin/src/main/resources/agents/(codex|claude)/[^/]+\\.(toml|md)");
	}

	/**
	 * Reads the selectable agent name from one immutable custom-agent manifest.
	 *
	 * @param blob the manifest blob
	 * @param path the manifest path for diagnostics
	 * @return the harness-neutral selectable name
	 * @throws IOException if the manifest has no supported name declaration
	 */
	private String customAgentName(String blob, String path) throws IOException
	{
		String contents = new String(git.bytes("cat-file", "-p", blob), UTF_8);
		for (String line : contents.split("\\R"))
		{
			String trimmed = line.trim();
			if (trimmed.startsWith("name = \"") && trimmed.endsWith("\""))
				return removeCatPrefix(trimmed.substring("name = \"".length(), trimmed.length() - 1));
			if (trimmed.startsWith("name:"))
			{
				String name = trimmed.substring("name:".length()).trim();
				if (!name.isEmpty())
					return removeCatPrefix(name.replace("\"", ""));
			}
		}
		throw new IOException("Custom-agent manifest must declare a selectable name: " + path);
	}

	/**
	 * Normalizes the harness-specific CAT prefix from an agent name.
	 *
	 * @param name a harness manifest's selectable agent name
	 * @return the shared logical agent name
	 */
	private String removeCatPrefix(String name)
	{
		if (name.startsWith("cat-"))
			return name.substring("cat-".length());
		return name;
	}

	/**
	 * One immutable before-and-after source-path change generated by Git.
	 *
	 * @param id the manifest-local inventory identifier
	 * @param source the source commit that changed the path
	 * @param path the repository-relative changed path
	 * @param beforeMode the Git mode before the source change, or {@code 000000} when absent
	 * @param beforeBlob the Git blob before the source change, or forty zeroes when absent
	 * @param afterMode the Git mode after the source change, or {@code 000000} when absent
	 * @param afterBlob the Git blob after the source change, or forty zeroes when absent
	 */
	record InventoryEntry(String id, String source, String path, String beforeMode, String beforeBlob,
		String afterMode, String afterBlob)
	{
	}
}
