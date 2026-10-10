package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the Git history facts that define one topic-replay range.
 */
public final class History
{
	public static final String ROOT_BASE = "--root";
	public static final String EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
	private final Path repository;
	private final Git git;
	/**
	 * Path states already read from a full object ID. An object ID names immutable content, so its path states cannot
	 * change; a symbolic revision such as {@code HEAD} is read on every request.
	 */
	private final PathStates pathStates = new PathStates();

	/**
	 * Creates a history reader for one replay repository.
	 *
	 * @param repository the replay repository working directory
	 * @param git Git access for the replay repository
	 */
	public History(Path repository, Git git)
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
		PlanningState.rejectRebase(isInteractiveRebaseInProgress());
		String actualTip = git.lines("rev-parse", "HEAD").getFirst();
		PlanningState.verifyTip(expectedTip, actualTip);
		PlanningState.rejectTrackedChanges(git.lines("status", "--porcelain", "--untracked-files=no"));
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
	public String resolveBase(String base) throws IOException
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
	public String baseTree(String base) throws IOException
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
		return git.lines("rev-list", "--reverse", revisionRange(base, tip));
	}

	/**
	 * Returns one ordered replay range with each commit's parents.
	 *
	 * @param base the resolved commit hash or {@value #ROOT_BASE}
	 * @param tip the range's final commit
	 * @return the ordered commits, each with its space-separated parent hashes
	 * @throws IOException if Git cannot read the range
	 */
	List<RangeCommit> rangeWithParents(String base, String tip) throws IOException
	{
		List<String> lines = git.lines("rev-list", "--reverse", "--parents", revisionRange(base, tip));
		List<RangeCommit> result = new ArrayList<>(lines.size());
		for (String line : lines)
		{
			int separator = line.indexOf(' ');
			if (separator == -1)
				result.add(new RangeCommit(line, ""));
			else
				result.add(new RangeCommit(line.substring(0, separator), line.substring(separator + 1)));
		}
		return result;
	}

	/**
	 * Returns the {@code git rev-list} revision argument that selects one replay range.
	 *
	 * @param base the resolved commit hash or {@value #ROOT_BASE}
	 * @param tip the range's final commit
	 * @return {@code tip} alone when the range starts at the root, otherwise {@code base..tip}
	 */
	private static String revisionRange(String base, String tip)
	{
		if (base.equals(ROOT_BASE))
			return tip;
		return base + ".." + tip;
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
	public List<InventoryEntry> inventory(String source) throws IOException
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
		return pathStates.read(revision, path, () -> readPathState(revision, path));
	}

	/**
	 * Reads a path's Git tree state at one revision.
	 *
	 * @param revision the Git revision
	 * @param path the repository-relative path
	 * @return the path state, or an absent state when the path is not in the revision
	 * @throws IOException if Git cannot inspect the revision
	 */
	private PathState readPathState(String revision, String path) throws IOException
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
		return EntryPoints.collect(inventory, blob -> new String(git.bytes("cat-file", "-p", blob), UTF_8));
	}

	/**
	 * One commit in an ordered replay range.
	 *
	 * @param hash the full commit hash
	 * @param parents the commit's space-separated parent hashes, or an empty string for a root commit
	 */
	public record RangeCommit(String hash, String parents)
	{
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
	public record InventoryEntry(String id, String source, String path, String beforeMode, String beforeBlob,
		String afterMode, String afterBlob)
	{
	}
}
