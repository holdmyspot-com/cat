package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.candidate.LiveGitInventory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** A retained, detached Git worktree created from a verified clean campaign base. */
final class VerifiedCleanFork
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String FILE_NAME = "clean-fork.json";

	/** Prevents construction of the fork transaction helper. */
	private VerifiedCleanFork()
	{
	}

	/**
	 * Checks the base before campaign publication and pins the exact Git tree and index.
	 *
	 * @param key original campaign lookup key
	 * @param directory eventual campaign directory
	 * @return durable fork intent
	 * @throws IOException if the base is dirty or Git cannot establish its identity
	 */
	static Identity preflight(CampaignLookupKey key, Path directory) throws IOException
	{
		Path base = key.canonicalProjectRoot();
		byte[] status = cleanStatus(base, directory.getParent());
		if (status.length != 0)
			throw new IOException("Base Git worktree has staged, unstaged, or untracked changes; " +
				"resolve them by committing, stashing, or removing them, then retry");
		String head = text(git(base, "rev-parse", "HEAD"));
		String index = sha256(git(base, "ls-files", "--stage", "-z"));
		LiveGitInventory source = LiveGitInventory.capture(base, directory.getParent());
		if (!source.headCommit().equals(head) || !source.indexSha256().equals(index))
			throw new IOException("Base Git worktree changed during clean-fork preflight; resolve it and retry");
		return new Identity(2, base, forkPath(base, directory), head, index,
			trackedEntries(source, base).stream().map(SourcePath::from).toList());
	}

	/**
	 * Reads user changes while omitting CAT's own untracked workflow files, if those files are not tracked.
	 *
	 * @param base selected original worktree
	 * @param workflowRoot CAT optimizer state root
	 * @return Git porcelain status for user-owned paths
	 * @throws IOException if Git cannot establish the status
	 */
	private static byte[] cleanStatus(Path base, Path workflowRoot) throws IOException
	{
		Path work = workflowRoot;
		if (work.getFileName() != null && work.getFileName().toString().equals("optimizer"))
			work = work.getParent();
		Path parent = work.getParent();
		if (work.getFileName() == null || !work.getFileName().toString().equals("work") ||
			parent == null || parent.getFileName() == null ||
			!parent.getFileName().toString().equals(".cat") || !work.startsWith(base))
			return git(base, "status", "--porcelain=v1", "-z", "--untracked-files=all");
		String relative = base.relativize(work).toString().replace('\\', '/');
		if (git(base, "ls-files", "-z", "--", relative).length != 0)
			return git(base, "status", "--porcelain=v1", "-z", "--untracked-files=all");
		return git(base, "status", "--porcelain=v1", "-z", "--untracked-files=all", "--", ".",
			":(exclude)" + relative + "/**");
	}

	/**
	 * Chooses a sibling worktree so relative links retain their original resolved destinations.
	 *
	 * @param base canonical original worktree
	 * @param directory campaign storage directory
	 * @return deterministic detached-worktree path
	 */
	static Path forkPath(Path base, Path directory)
	{
		String identity = sha256(directory.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
		return base.getParent().resolve("." + base.getFileName() + "-cat-optimization-" + identity);
	}

	/**
	 * Creates or verifies the same detached worktree from its pinned clean commit.
	 *
	 * @param identity retained fork identity
	 * @throws IOException if the fork is missing its exact Git tree or is redirected
	 */
	static void ensure(Identity identity) throws IOException
	{
		ensureAt(identity, identity.optimizationRoot());
	}

	/**
	 * Materializes the original Git tree for inspection or original-arm trials.
	 *
	 * @param identity retained fork identity
	 * @param snapshotId exact captured regular-source snapshot identity
	 * @return stable original worktree
	 * @throws IOException if its source Git tree is unavailable or altered
	 */
	static Path materializeOriginal(Identity identity, String snapshotId) throws IOException
	{
		String identityHash = sha256((identity.optimizationRoot() + ":" + snapshotId).
			getBytes(StandardCharsets.UTF_8)).substring(0, 16);
		Path original = identity.baseRoot().getParent().resolve("." + identity.baseRoot().getFileName() +
			"-cat-original-" + identityHash);
		ensureAt(identity, original);
		return original;
	}

	/**
	 * Creates or verifies one worktree at the pinned source commit.
	 *
	 * @param identity retained source identity
	 * @param fork campaign-owned worktree path
	 * @throws IOException if the path differs from the pinned Git tree
	 */
	static void ensureAt(Identity identity, Path fork) throws IOException
	{
		if (Files.notExists(fork, LinkOption.NOFOLLOW_LINKS))
		{
			if (!Files.isDirectory(fork.getParent(), LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Campaign directory is unavailable for its retained fork: " + fork);
			git(identity.baseRoot(), "worktree", "add", "--detach", "--force", fork.toString(),
				identity.headCommit());
			restoreModes(identity, fork);
		}
		if (!Files.isDirectory(fork, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(fork))
			throw new IOException("Campaign worktree is missing or redirected: " + fork);
		LiveGitInventory inventory = LiveGitInventory.capture(fork);
		if (!inventory.projectRoot().equals(fork) ||
			!inventory.headCommit().equals(identity.headCommit()) ||
			!inventory.indexSha256().equals(identity.indexSha256()) ||
			git(fork, "status", "--porcelain=v1", "-z", "--untracked-files=all").length != 0)
			throw new IOException("Campaign worktree conflicts with its retained clean fork: " + fork);
		verifyRegisteredWorktree(identity.baseRoot(), fork);
		if (inventory.paths().size() > identity.sourcePaths().size())
			throw new IOException("Campaign worktree has unowned paths: " + fork);
		List<LiveGitInventory.Entry> observed = inventory.paths();
		if (observed.size() != identity.sourcePaths().size())
			throw new IOException("Campaign worktree paths conflict with the retained clean fork: " + fork);
		for (int index = 0; index < observed.size(); ++index)
			verifyPath(identity, fork, identity.sourcePaths().get(index), observed.get(index));
	}

	/**
	 * Recreates an unfinished attempt checkout only when every remaining path matches its pinned source.
	 *
	 * @param identity retained clean source and Git repository
	 * @param worktree attempt path reserved before checkout
	 * @throws IOException if the path is foreign, edited, or cannot be recreated safely
	 */
	static void ensureAttemptAt(Identity identity, Path worktree) throws IOException
	{
		try
		{
			ensureAt(identity, worktree);
		}
		catch (IOException incomplete)
		{
			if (!Files.isDirectory(worktree, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(worktree))
				throw incomplete;
			LiveGitInventory observed;
			try
			{
				observed = LiveGitInventory.capture(worktree);
			}
			catch (IOException inspectionFailure)
			{
				incomplete.addSuppressed(inspectionFailure);
				throw incomplete;
			}
			verifyIncompleteAttempt(identity, worktree, observed);
			git(identity.baseRoot(), "worktree", "remove", "--force", worktree.toString());
			ensureAt(identity, worktree);
		}
	}

	/**
	 * Materializes a private attempt from the selected worktree's retained state.
	 *
	 * @param identity pinned clean source and Git repository
	 * @param worktree reserved private attempt path
	 * @param selected selected optimization worktree
	 * @param selectedInventory retained selected state to reproduce
	 * @throws IOException if the private worktree cannot be verified
	 */
	static void ensureAttemptAt(Identity identity, Path worktree, Path selected,
		LiveGitInventory selectedInventory) throws IOException
	{
		Objects.requireNonNull(selected, "selected");
		Objects.requireNonNull(selectedInventory, "selectedInventory");
		if (!selected.equals(identity.optimizationRoot()) ||
			!selectedInventory.projectRoot().equals(selected) ||
			!selectedInventory.headCommit().equals(identity.headCommit()) ||
			!LiveGitInventory.capture(selected).equals(selectedInventory))
			throw new IOException("Selected worktree differs from its retained replay inventory");
		ReplayCheckpoint checkpoint = ReplayCheckpoint.NONE;
		if (Files.isDirectory(worktree, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(worktree))
		{
			LiveGitInventory existing = LiveGitInventory.capture(worktree);
			verifyRegisteredWorktree(identity.baseRoot(), worktree);
			Set<Path> selectedPaths = new HashSet<>();
			for (LiveGitInventory.Entry entry : selectedInventory.paths())
				selectedPaths.add(entry.path());
			for (LiveGitInventory.Entry entry : existing.paths())
			{
				if (!selectedPaths.contains(entry.path()))
					throw new IOException("Private generation checkout has an unowned path: " + entry.path());
			}
			boolean complete;
			try
			{
				GenerationAttemptWorktreeService.verifyExactReplay(selectedInventory, existing);
				complete = true;
			}
			catch (IOException _)
			{
				complete = false;
			}
			if (complete)
			{
				if (!LiveGitInventory.capture(selected).equals(selectedInventory))
					throw new IOException("Selected worktree changed during private replay verification");
				return;
			}
			checkpoint = replayCheckpoint(identity, selected, worktree, existing, selectedInventory);
		}
		if (checkpoint == ReplayCheckpoint.NONE)
		{
			ensureAttemptAt(identity, worktree);
			applySelectedStagedChanges(selected, worktree);
		}
		if (checkpoint != ReplayCheckpoint.WORKTREE)
			applySelectedUnstagedChanges(selected, worktree);
		copyMissingSelectedPaths(selected, worktree, selectedInventory);
		restoreSelectedModes(worktree, selectedInventory);
		if (!LiveGitInventory.capture(selected).equals(selectedInventory))
			throw new IOException("Selected worktree changed during private replay");
		GenerationAttemptWorktreeService.verifyExactReplay(selectedInventory,
			LiveGitInventory.capture(worktree));
	}

	/**
	 * Identifies a completed replay checkpoint in a same-repository private worktree.
	 *
	 * @param identity pinned clean source
	 * @param selected source worktree
	 * @param worktree private attempt
	 * @param existing current private state
	 * @param selectedInventory retained selected state
	 * @return the last complete replay step whose state can be verified
	 */
	private static ReplayCheckpoint replayCheckpoint(Identity identity, Path selected, Path worktree,
		LiveGitInventory existing, LiveGitInventory selectedInventory)
	{
		if (!existing.headCommit().equals(identity.headCommit()) ||
			!existing.indexSha256().equals(selectedInventory.indexSha256()))
			return ReplayCheckpoint.NONE;
		try
		{
			if (!matchesCopiedUntrackedPaths(selected, worktree, existing, selectedInventory))
				return ReplayCheckpoint.NONE;
			byte[] privateDiff = git(worktree, "diff", "--binary", "--no-ext-diff", "--no-textconv");
			if (privateDiff.length == 0)
				return ReplayCheckpoint.INDEX;
			byte[] selectedDiff = git(selected, "diff", "--binary", "--no-ext-diff", "--no-textconv");
			if (Arrays.equals(privateDiff, selectedDiff))
				return ReplayCheckpoint.WORKTREE;
			return ReplayCheckpoint.NONE;
		}
		catch (IOException _)
		{
			return ReplayCheckpoint.NONE;
		}
	}

	/**
	 * Indicates whether existing untracked private paths are exact completed copies of selected paths.
	 *
	 * @param selected source worktree
	 * @param worktree private attempt
	 * @param existing current private paths
	 * @param inventory retained selected paths
	 * @return true when every untracked path may be retained during replay
	 * @throws IOException if Git cannot identify tracked private paths or a copied link is unsafe
	 */
	private static boolean matchesCopiedUntrackedPaths(Path selected, Path worktree,
		LiveGitInventory existing, LiveGitInventory inventory) throws IOException
	{
		Set<Path> tracked = new HashSet<>();
		for (LiveGitInventory.Entry entry : trackedEntries(existing, worktree))
			tracked.add(entry.path());
		Map<Path, LiveGitInventory.Entry> expected = new HashMap<>();
		for (LiveGitInventory.Entry entry : inventory.paths())
			expected.put(entry.path(), entry);
		for (LiveGitInventory.Entry entry : existing.paths())
		{
			if (tracked.contains(entry.path()))
				continue;
			LiveGitInventory.Entry source = expected.get(entry.path());
			if (source == null || source.kind() != entry.kind() ||
				!source.contentSha256().equals(entry.contentSha256()) ||
				!source.linkTarget().equals(entry.linkTarget()) ||
				(source.kind() == LiveGitInventory.Entry.Kind.REGULAR &&
				!source.permissions().equals(entry.permissions())))
				return false;
			if (source.kind() == LiveGitInventory.Entry.Kind.SYMLINK)
				GenerationAttemptWorktreeService.verifyPrivateLink(selected, worktree, source, entry);
		}
		return true;
	}

	/**
	 * Replay states safe to resume without replacing private files.
	 */
	private enum ReplayCheckpoint
	{
		NONE,
		INDEX,
		WORKTREE
	}

	/**
	 * Requires a linked worktree to remain registered at its reserved path in the source repository.
	 *
	 * @param baseRoot pinned source repository worktree
	 * @param worktree selected or private worktree
	 * @throws IOException if the worktree belongs to another repository or Git registration points elsewhere
	 */
	static void verifyRegisteredWorktree(Path baseRoot, Path worktree) throws IOException
	{
		Path baseGit = Path.of(text(git(baseRoot, "rev-parse", "--git-common-dir")));
		Path attemptGit = Path.of(text(git(worktree, "rev-parse", "--git-common-dir")));
		if (!baseRoot.resolve(baseGit).normalize().toRealPath().equals(
			worktree.resolve(attemptGit).normalize().toRealPath()))
			throw new IOException("Worktree belongs to another Git repository: " + worktree);
		Path gitFile = worktree.resolve(".git");
		Path gitDirectory = Path.of(text(git(worktree, "rev-parse", "--absolute-git-dir"))).toRealPath();
		Path registeredGitFile = gitDirectory.resolve("gitdir");
		if (!Files.isRegularFile(gitFile, LinkOption.NOFOLLOW_LINKS) ||
			!Files.isRegularFile(registeredGitFile, LinkOption.NOFOLLOW_LINKS) ||
			!Files.readString(registeredGitFile).strip().equals(gitFile.toString()))
			throw new IOException("Worktree is not registered at its reserved path: " + worktree);
	}

	/**
	 * Replays selected staged changes into a clean private index and worktree.
	 *
	 * @param selected source worktree with retained changes
	 * @param worktree clean private attempt
	 * @throws IOException if Git cannot reproduce the selected index
	 */
	private static void applySelectedStagedChanges(Path selected, Path worktree) throws IOException
	{
		byte[] staged = git(selected, "diff", "--binary", "--cached", "--no-ext-diff", "--no-textconv");
		if (staged.length != 0)
			gitWithInput(worktree, staged, "apply", "--index");
	}

	/**
	 * Replays selected unstaged changes after the private index matches the selected index.
	 *
	 * @param selected source worktree with retained changes
	 * @param worktree private attempt with the selected index
	 * @throws IOException if Git cannot reproduce the selected working files
	 */
	private static void applySelectedUnstagedChanges(Path selected, Path worktree) throws IOException
	{
		byte[] unstaged = git(selected, "diff", "--binary", "--no-ext-diff", "--no-textconv");
		if (unstaged.length != 0)
			gitWithInput(worktree, unstaged, "apply");
	}

	/**
	 * Copies selected paths absent after Git replay, including untracked directories and links.
	 *
	 * @param selected source worktree
	 * @param worktree private attempt worktree
	 * @param inventory retained selected paths
	 * @throws IOException if a path cannot be copied without following a link
	 */
	private static void copyMissingSelectedPaths(Path selected, Path worktree,
		LiveGitInventory inventory) throws IOException
	{
		for (LiveGitInventory.Entry entry : inventory.paths())
		{
			if (entry.kind() == LiveGitInventory.Entry.Kind.SYMLINK)
				continue;
			Path destination = worktree.resolve(entry.path());
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
				continue;
			Path source = selected.resolve(entry.path());
			switch (entry.kind())
			{
				case DIRECTORY -> Files.createDirectory(destination);
				case REGULAR -> Files.copy(source, destination, LinkOption.NOFOLLOW_LINKS,
					StandardCopyOption.COPY_ATTRIBUTES);
				case SYMLINK -> throw new AssertionError("Links are copied after ordinary paths");
			}
		}
		for (LiveGitInventory.Entry entry : inventory.paths())
		{
			if (entry.kind() != LiveGitInventory.Entry.Kind.SYMLINK)
				continue;
			Path destination = worktree.resolve(entry.path());
			if (Files.notExists(destination, LinkOption.NOFOLLOW_LINKS))
				Files.createSymbolicLink(destination, Files.readSymbolicLink(selected.resolve(entry.path())));
		}
	}

	/**
	 * Applies exact retained POSIX modes, including those Git's executable bit does not represent.
	 *
	 * @param worktree private attempt worktree
	 * @param inventory retained selected modes
	 * @throws IOException if a selected path or its mode cannot be reproduced
	 */
	private static void restoreSelectedModes(Path worktree, LiveGitInventory inventory) throws IOException
	{
		List<LiveGitInventory.Entry> entries = new ArrayList<>(inventory.paths());
		entries.sort(Comparator.comparingInt((LiveGitInventory.Entry entry) -> entry.path().getNameCount()).
			reversed());
		for (LiveGitInventory.Entry entry : entries)
		{
			if (entry.kind() != LiveGitInventory.Entry.Kind.SYMLINK)
				Files.setPosixFilePermissions(worktree.resolve(entry.path()),
					PosixFilePermissions.fromString(entry.permissions()));
		}
	}

	/**
	 * Runs a Git command with a bounded patch on standard input.
	 *
	 * @param worktree private attempt worktree
	 * @param input exact binary patch from the selected worktree
	 * @param arguments Git arguments
	 * @throws IOException if the patch is rejected or Git cannot finish
	 */
	private static void gitWithInput(Path worktree, byte[] input, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		try (Process process = new ProcessBuilder(command).directory(worktree.toFile()).
			redirectErrorStream(true).start())
		{
			process.getOutputStream().write(input);
			process.getOutputStream().close();
			byte[] output = process.getInputStream().readNBytes(64 * 1024 * 1024 + 1);
			if (output.length > 64 * 1024 * 1024)
			{
				process.destroyForcibly();
				throw new IOException("Git replay command output exceeded its bound: " + command);
			}
			if (process.waitFor() != 0)
				throw new IOException("Git replay command failed: " + command + ": " + text(output));
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Git replay command was interrupted: " + command, e);
		}
	}

	/**
	 * Allows only missing tracked paths in a same-repository checkout that has never reached readiness.
	 *
	 * @param identity retained clean source and Git repository
	 * @param worktree reserved attempt path
	 * @param observed remaining no-follow path states
	 * @throws IOException if an existing path or Git identity differs from the pinned source
	 */
	private static void verifyIncompleteAttempt(Identity identity, Path worktree,
		LiveGitInventory observed) throws IOException
	{
		if (!observed.projectRoot().equals(worktree) ||
			!observed.headCommit().equals(identity.headCommit()) ||
			!observed.indexSha256().equals(identity.indexSha256()))
			throw new IOException("Private generation checkout has a foreign Git identity: " + worktree);
		verifyRegisteredWorktree(identity.baseRoot(), worktree);

		Map<Path, SourcePath> expected = new HashMap<>();
		for (SourcePath source : identity.sourcePaths())
			expected.put(Path.of(source.path()), source);
		for (LiveGitInventory.Entry entry : observed.paths())
		{
			SourcePath source = expected.remove(entry.path());
			if (source == null)
				throw new IOException("Private generation checkout has an unowned path: " + entry.path());
			verifyPath(identity, worktree, source, entry);
		}
		if (expected.isEmpty())
			throw new IOException("Private generation checkout is inconsistent without missing source paths: " +
				worktree);
	}

	/**
	 * Reapplies source POSIX modes that Git does not represent beyond the executable bit.
	 *
	 * @param identity exact source path state
	 * @param fork newly checked-out worktree
	 * @throws IOException if a mode cannot be reproduced
	 */
	private static void restoreModes(Identity identity, Path fork) throws IOException
	{
		List<SourcePath> modes = new ArrayList<>(identity.sourcePaths());
		modes.sort(Comparator.comparingInt((SourcePath path) -> Path.of(path.path()).getNameCount()).reversed());
		for (SourcePath entry : modes)
		{
			if (entry.kind() != LiveGitInventory.Entry.Kind.SYMLINK)
				Files.setPosixFilePermissions(fork.resolve(Path.of(entry.path())),
					PosixFilePermissions.fromString(entry.permissions()));
		}
	}

	/**
	 * Checks one path's exact kind, bytes, mode, link text, and corresponding resolved target.
	 *
	 * @param identity pinned original source
	 * @param fork destination worktree root
	 * @param source original path state
	 * @param observed destination path state
	 * @throws IOException if the path differs
	 */
	private static void verifyPath(Identity identity, Path fork, SourcePath source,
		LiveGitInventory.Entry observed) throws IOException
	{
		if (!Path.of(source.path()).equals(observed.path()) || source.kind() != observed.kind() ||
			!source.contentSha256().equals(observed.contentSha256()) ||
			!source.permissions().equals(observed.permissions()) ||
			!source.linkTarget().equals(observed.linkTarget()))
			throw new IOException("Campaign worktree path conflicts with the retained clean fork: " + source.path());
		String expectedResolved = source.resolvedTarget();
		if (!expectedResolved.isEmpty())
		{
			Path resolved = Path.of(expectedResolved);
			if (resolved.startsWith(identity.baseRoot()))
				expectedResolved = fork.resolve(identity.baseRoot().relativize(resolved)).toString();
		}
		if (!expectedResolved.equals(observed.resolvedTarget()))
			throw new IOException("Campaign worktree link destination conflicts with the retained clean fork: " +
				source.path());
	}

	/**
	 * Selects Git-tracked source paths and their directories from a complete no-follow inventory.
	 *
	 * @param inventory observed worktree state
	 * @param worktree Git worktree root
	 * @return tracked paths in stable order
	 * @throws IOException if a tracked path is absent or cannot be enumerated
	 */
	private static List<LiveGitInventory.Entry> trackedEntries(LiveGitInventory inventory, Path worktree)
		throws IOException
	{
		byte[] names = git(worktree, "ls-files", "-z");
		Set<Path> tracked = new HashSet<>();
		int beginning = 0;
		for (int index = 0; index < names.length; ++index)
		{
			if (names[index] != 0)
				continue;
			Path path = Path.of(new String(names, beginning, index - beginning, StandardCharsets.UTF_8));
			while (path != null)
			{
				tracked.add(path);
				path = path.getParent();
			}
			beginning = index + 1;
		}
		if (beginning != names.length)
			throw new IOException("Git tracked-path listing is incomplete: " + worktree);
		List<LiveGitInventory.Entry> selected = inventory.paths().stream().filter(entry ->
			tracked.contains(entry.path())).toList();
		if (selected.size() != tracked.size())
			throw new IOException("Git tracked paths differ from the captured worktree: " + worktree);
		return selected;
	}

	/**
	 * Reads and validates a campaign's retained fork identity.
	 *
	 * @param directory campaign directory
	 * @param base original campaign lookup root
	 * @return pinned fork identity
	 * @throws IOException if identity is absent, malformed, or foreign
	 */
	static Identity load(Path directory, Path base) throws IOException
	{
		Path file = directory.resolve(FILE_NAME);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Campaign clean-fork identity is missing: " + file);
		try
		{
			Identity identity = JSON.readValue(Files.readAllBytes(file), Identity.class);
			if (identity == null ||
				!identity.baseRoot().equals(base) ||
				!identity.optimizationRoot().equals(forkPath(base, directory)))
				throw new IOException("Campaign clean-fork identity is malformed or foreign: " + file);
			return identity;
		}
		catch (RuntimeException e)
		{
			throw new IOException("Campaign clean-fork identity is malformed: " + file, e);
		}
	}

	/**
	 * Writes the identity only after it has been pinned in the creation transaction.
	 *
	 * @param identity pinned clean-fork intent
	 * @param directory campaign directory
	 * @throws IOException if publication fails or an earlier identity conflicts
	 */
	static void publish(Identity identity, Path directory) throws IOException
	{
		Path file = directory.resolve(FILE_NAME);
		if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
		{
			if (!load(directory, identity.baseRoot()).equals(identity))
				throw new IOException("Campaign clean-fork identity conflicts with its intent: " + file);
			return;
		}
		CampaignLocator.writeAtomically(file, JSON.writeValueAsBytes(identity));
	}

	/**
	 * Encodes an intent in the locked locator transaction.
	 *
	 * @param identity pinned Git source
	 * @return canonical identity bytes
	 */
	static byte[] encode(Identity identity)
	{
		return JSON.writeValueAsBytes(identity);
	}

	/**
	 * Reads the retained pre-registration intent.
	 *
	 * @param file intent path
	 * @return pinned Git source
	 * @throws IOException if the intent is malformed
	 */
	static Identity readIntent(Path file) throws IOException
	{
		try
		{
			Identity identity = JSON.readValue(Files.readAllBytes(file), Identity.class);
			if (identity == null)
				throw new IOException("Campaign clean-fork intent is malformed: " + file);
			return identity;
		}
		catch (RuntimeException e)
		{
			throw new IOException("Campaign clean-fork intent is malformed: " + file, e);
		}
	}

	/**
	 * Decodes one bounded Git response.
	 *
	 * @param value Git stdout bytes
	 * @return trimmed UTF-8 text
	 */
	private static String text(byte[] value)
	{
		return new String(value, StandardCharsets.UTF_8).strip();
	}

	/**
	 * Hashes the exact staged index listing, including paths and file modes.
	 *
	 * @param bytes staged index listing
	 * @return SHA-256 identity
	 */
	private static String sha256(byte[] bytes)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Runs a bounded Git operation without a shell.
	 *
	 * @param directory source or optimization worktree
	 * @param arguments Git arguments
	 * @return command output
	 * @throws IOException if Git fails or exceeds the output bound
	 */
	private static byte[] git(Path directory, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		try (Process process = new ProcessBuilder(command).directory(directory.toFile()).
			redirectErrorStream(true).start())
		{
			byte[] output = process.getInputStream().readNBytes(64 * 1024 * 1024 + 1);
			if (output.length > 64 * 1024 * 1024)
			{
				process.destroyForcibly();
				throw new IOException("Git fork command output exceeded its bound: " + command);
			}
			if (process.waitFor() != 0)
				throw new IOException("Git fork command failed: " + command + ": " + text(output));
			return output;
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Git fork command was interrupted: " + command, e);
		}
	}

	/**
	 * Pinned source and detached optimization-worktree identity.
	 *
	 * @param schemaVersion exact identity schema
	 * @param baseRoot original canonical campaign root
	 * @param optimizationRoot campaign-owned detached worktree
	 * @param headCommit original clean Git commit
	 * @param indexSha256 exact Git index listing hash, including modes
	 * @param sourcePaths exact tracked path, byte, mode, and link identity states
	 */
	record Identity(int schemaVersion, Path baseRoot, Path optimizationRoot, String headCommit,
		String indexSha256, List<SourcePath> sourcePaths)
	{
		/**
		 * Validates the retained source identity.
		 *
		 * @param schemaVersion exact identity schema
		 * @param baseRoot original canonical campaign root
		 * @param optimizationRoot campaign-owned detached worktree
		 * @param headCommit original clean Git commit
		 * @param indexSha256 exact Git index listing hash
		 * @param sourcePaths exact tracked source paths, modes, and link identities
		 */
		Identity
		{
			if (schemaVersion != 2 || baseRoot == null || optimizationRoot == null ||
				!baseRoot.isAbsolute() || !baseRoot.equals(baseRoot.normalize()) ||
				!optimizationRoot.isAbsolute() || !optimizationRoot.equals(optimizationRoot.normalize()) ||
				Objects.equals(baseRoot, optimizationRoot) || headCommit == null ||
				!headCommit.matches("[0-9a-f]{40,64}") || indexSha256 == null ||
				!indexSha256.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("Clean-fork identity is invalid");
			sourcePaths = List.copyOf(Objects.requireNonNull(sourcePaths, "sourcePaths"));
		}
	}

	/**
	 * JSON-stable path state whose relative path remains relative across process restarts.
	 *
	 * @param path normalized project-relative path
	 * @param kind no-follow source kind
	 * @param contentSha256 exact regular bytes or link-text hash
	 * @param permissions exact POSIX mode for a regular file or directory
	 * @param linkTarget original symbolic-link text
	 * @param resolvedTarget original observable resolved target
	 */
	record SourcePath(String path, LiveGitInventory.Entry.Kind kind, String contentSha256,
		String permissions, String linkTarget, String resolvedTarget)
	{
		/**
		 * Validates one persisted source path.
		 *
		 * @param path normalized project-relative path
		 * @param kind no-follow source kind
		 * @param contentSha256 exact regular bytes or link-text hash
		 * @param permissions exact POSIX mode for a regular file or directory
		 * @param linkTarget original symbolic-link text
		 * @param resolvedTarget original observable resolved target
		 */
		SourcePath
		{
			Path relative = Path.of(Objects.requireNonNull(path, "path"));
			if (relative.isAbsolute() || !relative.equals(relative.normalize()) ||
				relative.toString().isEmpty() || relative.startsWith(".."))
				throw new IllegalArgumentException("Clean-fork source path is invalid: " + path);
			new LiveGitInventory.Entry(relative, kind, contentSha256, permissions, linkTarget, resolvedTarget);
		}

		/**
		 * Converts an observed Git inventory entry to a JSON-stable source record.
		 *
		 * @param entry observed source entry
		 * @return retained path state
		 */
		static SourcePath from(LiveGitInventory.Entry entry)
		{
			return new SourcePath(entry.path().toString(), entry.kind(), entry.contentSha256(),
				entry.permissions(), entry.linkTarget(), entry.resolvedTarget());
		}
	}
}
