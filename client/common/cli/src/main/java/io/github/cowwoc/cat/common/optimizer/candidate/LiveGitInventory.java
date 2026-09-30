package io.github.cowwoc.cat.common.optimizer.candidate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Complete before-call worktree paths plus HEAD and index identities, without mutating Git.
 *
 * @param schemaVersion exact inventory schema
 * @param projectRoot pinned active Git worktree
 * @param headCommit current HEAD identity
 * @param indexSha256 exact staged index listing hash
 * @param paths sorted complete non-Git worktree path states
 */
public record LiveGitInventory(int schemaVersion, Path projectRoot, String headCommit, String indexSha256,
	List<Entry> paths)
{
	/** The only supported inventory schema. */
	public static final int SCHEMA_VERSION = 2;

	/** Validates complete ordered inventory identity. */
	public LiveGitInventory
	{
		if (schemaVersion != SCHEMA_VERSION || projectRoot == null || !projectRoot.isAbsolute() ||
			!projectRoot.equals(projectRoot.normalize()) || headCommit == null || headCommit.isBlank() ||
			!PromptSnapshot.isSha256(indexSha256))
			throw new IllegalArgumentException("Live Git inventory identity is invalid");
		paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
		Path prior = null;
		for (Entry entry: paths)
		{
			if (prior != null && prior.toString().compareTo(entry.path().toString()) >= 0)
				throw new IllegalArgumentException("Live Git inventory paths must be sorted and unique");
			prior = entry.path();
		}
	}

	/**
	 * Captures all user paths without following links or modifying HEAD, index, or files.
	 *
	 * @param requestedProject selected active Git worktree
	 * @return complete pinned inventory
	 * @throws IOException if Git identity or a file kind is unsupported
	 */
	public static LiveGitInventory capture(Path requestedProject) throws IOException
	{
		return capture(requestedProject, Optional.empty());
	}

	/**
	 * Captures user paths while excluding one internal workflow directory from the source tree.
	 *
	 * @param requestedProject selected active Git worktree
	 * @param excludedDirectory absolute internal directory to omit when it lies under the project
	 * @return pinned inventory without internal workflow files
	 * @throws IOException if Git identity or a file kind is unsupported
	 */
	public static LiveGitInventory capture(Path requestedProject, Path excludedDirectory) throws IOException
	{
		Objects.requireNonNull(excludedDirectory, "excludedDirectory");
		if (!excludedDirectory.isAbsolute() || !excludedDirectory.equals(excludedDirectory.normalize()))
			throw new IllegalArgumentException("Excluded workflow directory must be absolute and normalized");
		return capture(requestedProject, Optional.of(excludedDirectory));
	}

	/**
	 * Performs the Git and no-follow worktree inventory with an optional internal exclusion.
	 *
	 * @param requestedProject selected active Git worktree
	 * @param excludedDirectory internal workflow directory, when known
	 * @return pinned inventory
	 * @throws IOException if Git identity or a file kind is unsupported
	 */
	private static LiveGitInventory capture(Path requestedProject, Optional<Path> excludedDirectory)
		throws IOException
	{
		Path project = requestedProject.toRealPath();
		if (excludedDirectory.isPresent() && project.startsWith(excludedDirectory.orElseThrow()))
			throw new IOException("Workflow exclusion contains the selected project");
		Path topLevel = Path.of(new String(git(project, "rev-parse", "--show-toplevel"), StandardCharsets.UTF_8).
			strip()).toRealPath();
		if (!topLevel.equals(project))
			throw new IOException("Selected project is not the active Git worktree root");
		String head = new String(git(project, "rev-parse", "HEAD"), StandardCharsets.UTF_8).strip();
		String indexHash = PromptSnapshot.sha256(git(project, "ls-files", "--stage", "-z"));
		List<Entry> entries = new ArrayList<>();
		Files.walkFileTree(project, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException
			{
				if (directory.equals(project))
					return FileVisitResult.CONTINUE;
				if (excludedDirectory.isPresent() && directory.equals(excludedDirectory.orElseThrow()))
					return FileVisitResult.SKIP_SUBTREE;
				if (directory.getFileName().toString().equals(".git") && directory.getParent().equals(project))
					return FileVisitResult.SKIP_SUBTREE;
				entries.add(new Entry(project.relativize(directory), Entry.Kind.DIRECTORY, "",
					permissions(directory), "", ""));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				if (excludedDirectory.isPresent() && file.equals(excludedDirectory.orElseThrow()))
					return FileVisitResult.CONTINUE;
				if (file.getFileName().toString().equals(".git") && file.getParent().equals(project))
					return FileVisitResult.CONTINUE;
				if (attributes.isRegularFile())
					entries.add(new Entry(project.relativize(file), Entry.Kind.REGULAR, hashRegular(file),
						permissions(file), "", ""));
				else if (attributes.isSymbolicLink())
				{
					String linkTarget = Files.readSymbolicLink(file).toString();
					String resolvedTarget;
					try
					{
						resolvedTarget = file.toRealPath().toString();
					}
					catch (IOException _)
					{
						resolvedTarget = "";
					}
					entries.add(new Entry(project.relativize(file), Entry.Kind.SYMLINK,
						PromptSnapshot.sha256(linkTarget.getBytes(StandardCharsets.UTF_8)), "", linkTarget,
						resolvedTarget));
				}
				else
					throw new IOException("Unsupported active worktree artifact: " + file);
				return FileVisitResult.CONTINUE;
			}
		});
		entries.sort(Comparator.comparing(entry -> entry.path().toString()));
		return new LiveGitInventory(SCHEMA_VERSION, project, head, indexHash, entries);
	}

	/**
	 * Reads a file or directory's exact POSIX mode without following a symbolic link.
	 *
	 * @param path worktree path
	 * @return nine-character POSIX permission string
	 * @throws IOException if its mode cannot be read
	 */
	private static String permissions(Path path) throws IOException
	{
		try
		{
			return PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
		}
		catch (UnsupportedOperationException e)
		{
			throw new IOException("Cannot verify exact POSIX file mode: " + path, e);
		}
	}

	/**
	 * Runs one read-only Git command without a shell.
	 *
	 * @param project selected active Git worktree
	 * @param arguments exact Git arguments
	 * @return bounded command output
	 * @throws IOException if Git fails or output exceeds the bound
	 */
	private static byte[] git(Path project, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		try (Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start())
		{
			byte[] output = process.getInputStream().readNBytes(64 * 1024 * 1024 + 1);
			if (output.length > 64 * 1024 * 1024)
			{
				process.destroyForcibly();
				throw new IOException("Git inventory command exceeded its output bound: " + command);
			}
			if (process.waitFor() != 0)
				throw new IOException("Git inventory command failed or exceeded its output bound: " + command);
			return output;
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Git inventory command was interrupted", e);
		}
	}

	/**
	 * Streams one regular file through a no-follow descriptor.
	 *
	 * @param file regular worktree path
	 * @return SHA-256 of exact file bytes
	 * @throws IOException if the path changes or cannot be read safely
	 */
	private static String hashRegular(Path file) throws IOException
	{
		MessageDigest digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
		{
			ByteBuffer buffer = ByteBuffer.allocate(8192);
			while (channel.read(buffer) != -1)
			{
				buffer.flip();
				digest.update(buffer);
				buffer.clear();
			}
		}
		return java.util.HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * One project-relative worktree entry.
	 *
	 * @param path normalized project-relative path
	 * @param kind directory, regular file, or symlink
	 * @param contentSha256 file bytes or link-target hash, empty only for directories
	 * @param permissions exact POSIX mode of a regular file or directory
	 * @param linkTarget raw symbolic-link destination, empty for non-links
	 * @param resolvedTarget resolved symbolic-link destination when observable, empty otherwise
	 */
	public record Entry(Path path, Kind kind, String contentSha256, String permissions,
		String linkTarget, String resolvedTarget)
	{
		/** Validates one inventory path state. */
		public Entry
		{
			DeliveryPath.validateRelative(path);
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(contentSha256, "contentSha256");
			Objects.requireNonNull(permissions, "permissions");
			Objects.requireNonNull(linkTarget, "linkTarget");
			Objects.requireNonNull(resolvedTarget, "resolvedTarget");
			if (kind == Kind.DIRECTORY && !contentSha256.isEmpty() ||
				kind != Kind.DIRECTORY && !PromptSnapshot.isSha256(contentSha256) ||
				kind == Kind.SYMLINK && (!permissions.isEmpty() || linkTarget.isEmpty()) ||
				kind != Kind.SYMLINK && (!permissions.matches("[rwx-]{9}") || !linkTarget.isEmpty() ||
					!resolvedTarget.isEmpty()))
				throw new IllegalArgumentException("Live Git entry hash disagrees with kind");
		}

		/** Supported no-follow worktree kinds. */
		public enum Kind
		{
			DIRECTORY,
			REGULAR,
			SYMLINK
		}
	}
}
