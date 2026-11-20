package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Copies a session folder to where its requester reads it, leaving out links, the child's isolated home, which holds
 * its logins, and the project files that the child's sandbox kept it from changing.
 */
public final class ResultsCopy
{
	/**
	 * The directory of a session folder that holds the child's isolated home, which is never copied.
	 */
	private static final String SESSION_HOME = "home";
	/**
	 * The directory of a session folder that holds the copy of the project that the child worked in.
	 */
	private static final String SESSION_PROJECT = "project";
	/**
	 * The directory of a session folder that holds the copy of the project as it was before the child's first turn.
	 */
	private static final String SESSION_INITIAL_PROJECT = "initial-project";
	/**
	 * The manifest of a nested session, which {@link #copy(Path, Path, String, Path)} writes after the rest of the nested
	 * session.
	 */
	private static final Path MANIFEST = Path.of("session.json");
	private static final String SESSION_ARTIFACTS = "artifacts";
	private static final String SESSION_TRANSCRIPTS = "session-transcripts";
	/**
	 * The directories of a nested session that {@link #copyTurn(Path, Path)} replaces.
	 */
	private static final Set<String> TURN_COPIES = Set.of(SESSION_PROJECT, SESSION_INITIAL_PROJECT);

	/**
	 * How the copy of a session folder ended.
	 *
	 * @param incomplete the message that names each file that could not be copied with the failure that stopped it and
	 *                   says that the results copy is incomplete, or empty if the nested session was copied in full,
	 *                   apart from what the copier's sandbox protects
	 * @param skipped    the paths, relative to the nested session, that the copier's sandbox refused to let the copy
	 *                   write
	 */
	public record Result(Optional<String> incomplete, List<String> skipped)
	{
		/**
		 * Creates a result.
		 *
		 * @param incomplete the message that names each file that could not be copied, or empty
		 * @param skipped    the paths, relative to the nested session, that the copy skipped
		 * @throws NullPointerException if {@code incomplete} or {@code skipped} are null
		 */
		public Result
		{
			requireThat(incomplete, "incomplete").isNotNull();
			skipped = List.copyOf(skipped);
		}
	}

	/**
	 * Copies a session folder into {@code target}, leaving out links, the child's isolated home and the project files
	 * that the child's sandbox kept it from changing. A file or folder that the copier's sandbox refuses to write is
	 * skipped without a message and listed in the result. Any other file or folder that cannot be copied is left out and
	 * reported with the failure that stopped it, and the copy goes on with the rest of the nested session, so that one
	 * such file costs the requester neither the nested session's project nor its other evidence. The failure is reported,
	 * not thrown, so that it does not hide the session's outcome. Only a session folder whose own folder cannot be read,
	 * or a copy whose own folder cannot be created, ends the copy at once.
	 *
	 * @param source the session folder
	 * @param target the copy, which need not exist
	 * @return how the copy ended
	 * @throws NullPointerException if {@code source} or {@code target} are null
	 */
	public static Result copy(Path source, Path target)
	{
		requireThat(source, "source").isNotNull();
		requireThat(target, "target").isNotNull();
		return copy(source, target, _ -> true);
	}

	/**
	 * Copies a session folder as {@link #copy(Path, Path)} does and gives the copy the identifier under which it is kept
	 * and the project that its requester named. The nested session's runner ran the session in the copy of that project
	 * that the request carried, so the copy's manifest names the requester's project instead, as the manifest of a nested
	 * session that the requester started itself would, and a removal of the requester's nested sessions by their project
	 * finds it. The copy's manifest appears last and whole, already naming {@code id}, so that a process that lists the
	 * results folder while the copy is under way never reads a manifest that is partial or names the nested session's
	 * slot.
	 *
	 * @param source  the session folder
	 * @param target  the copy, which need not exist
	 * @param id      the identifier under which the copy is kept
	 * @param project the project that the requester named
	 * @return how the copy ended
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code id} is not a slot ID
	 * @throws IOException              if the nested session's manifest is malformed or cannot be written
	 */
	public static Result copy(Path source, Path target, String id, Path project) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(target, "target").isNotNull();
		requireThat(id, "id").isNotNull();
		requireThat(project, "project").isNotNull();
		Result result = copy(source, target, relative -> !relative.equals(MANIFEST));
		NestedSessionManager.copyManifest(source, target, id, project);
		return result;
	}

	/**
	 * Replaces a nested session's project, initial project and native session transcripts with those of another nested
	 * session, which the requester reads while the other nested session's harness session is still working. Other
	 * artifacts remain untouched.
	 *
	 * @param source the nested session whose turn evidence is copied
	 * @param target the nested session that receives it, which need not exist
	 * @return the immutable list of relative project paths the copier skipped because its sandbox refused the writes
	 * @throws NullPointerException if {@code source} or {@code target} are null
	 * @throws IOException          if the target's earlier copy cannot be removed, or a file cannot be copied
	 */
	public static List<String> copyTurn(Path source, Path target) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(target, "target").isNotNull();
		deleteTurnCopies(target);
		ResultsCopier copier = new ResultsCopier(source, target, ResultsCopy::includesTurnPath,
			ResultsCopy::isRequiredTranscript, false);
		Files.walkFileTree(source, copier);
		return List.copyOf(copier.skipped);
	}

	/**
	 * Removes the project, initial project and session transcript copies from a nested session's copy, so that a copy of
	 * the whole nested session can follow.
	 *
	 * @param sessionFolder the nested session's copy
	 * @throws NullPointerException if {@code sessionFolder} is null
	 * @throws IOException          if a file cannot be removed
	 */
	public static void deleteTurnCopies(Path sessionFolder) throws IOException
	{
		requireThat(sessionFolder, "sessionFolder").isNotNull();
		for (String name : TURN_COPIES)
			TemporaryDirectories.deleteRecursively(sessionFolder.resolve(name));
		TemporaryDirectories.deleteRecursively(sessionFolder.resolve(SESSION_ARTIFACTS).resolve(SESSION_TRANSCRIPTS));
	}

	/**
	 * Indicates whether a path belongs to the evidence that changes during a turn.
	 *
	 * @param relative the path relative to the nested session
	 * @return whether the path is a project, initial project, artifacts directory or session transcript
	 */
	private static boolean includesTurnPath(Path relative)
	{
		if (relative.toString().isEmpty())
			return true;
		String first = relative.getName(0).toString();
		return TURN_COPIES.contains(first) || first.equals(SESSION_ARTIFACTS) &&
			(relative.getNameCount() == 1 || relative.getName(1).toString().equals(SESSION_TRANSCRIPTS));
	}

	/**
	 * Indicates whether a copy failure must abort turn evidence publication.
	 *
	 * @param relative the path relative to the nested session
	 * @return whether the path is a native session transcript path
	 */
	private static boolean isRequiredTranscript(Path relative)
	{
		return relative.getNameCount() == 1 && relative.getName(0).toString().equals(SESSION_ARTIFACTS) ||
			relative.getNameCount() >= 2 && relative.getName(0).toString().equals(SESSION_ARTIFACTS) &&
				relative.getName(1).toString().equals(SESSION_TRANSCRIPTS);
	}

	/**
	 * Copies the directories of a session folder that a filter accepts.
	 *
	 * @param source   the session folder
	 * @param target   the copy, which need not exist
	 * @param included accepts the name of each directory directly below the nested session that the copy includes
	 * @return how the copy ended
	 */
	private static Result copy(Path source, Path target, Predicate<Path> included)
	{
		ResultsCopier copier = new ResultsCopier(source, target, included, _ -> false, true);
		try
		{
			Files.walkFileTree(source, copier);
		}
		catch (IOException e)
		{
			copier.failures.add(failure(copier.attempted, e));
		}
		if (copier.failures.isEmpty())
			return new Result(Optional.empty(), copier.skipped);
		return new Result(Optional.of(incompleteCopy(copier.failures, target)), copier.skipped);
	}

	/**
	 * Indicates whether a failure is the sandbox of the process that copies refusing to let it write.
	 *
	 * @param failure the failure
	 * @return {@code true} if the operating system refused the operation for lack of permission
	 */
	private static boolean isPermissionRefusal(IOException failure)
	{
		return failure instanceof AccessDeniedException ||
			failure instanceof FileSystemException e && "Operation not permitted".equals(e.getReason());
	}

	/**
	 * Describes a file that could not be copied.
	 *
	 * @param file    the file or folder of the copy that could not be written
	 * @param failure the failure that stopped it, which names the file that it concerns, the copy's or the session
	 *                folder's, and the system's reason
	 * @return the description
	 */
	private static String failure(Path file, IOException failure)
	{
		return "The file " + file + " could not be copied: " + failure + ".";
	}

	/**
	 * Returns the message that says a results copy is incomplete.
	 *
	 * @param failures the description of each file that could not be copied
	 * @param target   the results copy
	 * @return the message
	 */
	private static String incompleteCopy(List<String> failures, Path target)
	{
		return String.join("\n", failures) + "\nThe results copy at " + target + " is incomplete.";
	}

	/**
	 * Copies a session folder's directories and regular files, skips what the copier's sandbox refuses to write, and
	 * remembers which copy it attempted last. A copier that goes on past failures leaves out each other file or folder
	 * that cannot be copied and remembers why; any other copier stops at the first.
	 * <p>
	 * <b>Thread Safety:</b> This class is not thread-safe.
	 */
	private static final class ResultsCopier extends SimpleFileVisitor<Path>
	{
		/**
		 * The files and folders of the nested session, relative to it, that the child's sandbox kept it from changing, so
		 * that they equal the initial project's and nothing reads them from a session folder. The copy leaves them out
		 * because the requester's own sandbox can refuse to write them, as Claude Code's does for its settings and a
		 * project's Git configuration and hooks, and a child is never allowed more than its parent: it would otherwise
		 * change a file that the parent cannot. A copier outside that sandbox must not write them where the requester reads
		 * the nested session either, which would let the child write where the requester's sandbox forbids it.
		 */
		private static final Set<Path> UNCHANGED = ProtectedProjectFiles.paths().stream().
			map(path -> Path.of(SESSION_PROJECT).resolve(path)).collect(Collectors.toUnmodifiableSet());
		/**
		 * The session folder that is copied.
		 */
		private final Path source;
		/**
		 * The results copy.
		 */
		private final Path target;
		/**
		 * Accepts the name of each directory directly below the nested session that the copy includes.
		 */
		private final Predicate<Path> included;
		private final Predicate<Path> required;
		/**
		 * Indicates whether a file or folder that cannot be copied is left out, rather than ending the copy, unless
		 * {@link #required} accepts it.
		 */
		private final boolean goesOnPastFailures;
		/**
		 * The paths, relative to the nested session, that the copier's sandbox refused to let the copy write.
		 */
		private final List<String> skipped = new ArrayList<>();
		/**
		 * The description of each file or folder that was left out because it could not be copied.
		 */
		private final List<String> failures = new ArrayList<>();
		/**
		 * The file or directory of the copy that the visitor last tried to write.
		 */
		private Path attempted;

		/**
		 * Creates a copier.
		 *
		 * @param source the session folder that is copied
		 * @param target the results copy
		 * @param included accepts the paths relative to the nested session that the copy includes
		 * @param required accepts the paths whose copy failures must be thrown
		 * @param goesOnPastFailures indicates whether a file or folder that cannot be copied is left out, rather than
		 *                           ending the copy, unless {@code required} accepts it
		 */
		ResultsCopier(Path source, Path target, Predicate<Path> included, Predicate<Path> required,
			boolean goesOnPastFailures)
		{
			this.source = source;
			this.target = target;
			this.included = included;
			this.required = required;
			this.goesOnPastFailures = goesOnPastFailures;
			this.attempted = target;
		}

		/**
		 * Indicates whether the copy includes a path of the session folder.
		 *
		 * @param relative the path, relative to the nested session
		 * @return {@code true} for the nested session itself and for every path whose first name the filter accepts
		 */
		private boolean includes(Path relative)
		{
			return relative.toString().isEmpty() ||
				!RetainedTrialPrefix.isPrivateSnapshot(relative) && included.test(relative);
		}

		/**
		 * Returns where a file of the session folder goes in the copy.
		 *
		 * @param file the file that is copied
		 * @return its place in the copy
		 */
		private Path copyOf(Path file)
		{
			return target.resolve(source.relativize(file).toString());
		}

		/**
		 * Indicates whether a failure to write a file or folder of the copy is the copier's sandbox refusing
		 * it, and if so, remembers that the copy skips it. A refusal to read the session folder's file is not one.
		 *
		 * @param file    the file or folder that is copied
		 * @param failure the failure
		 * @return {@code true} if the copy skips the file or folder
		 */
		private boolean skips(Path file, IOException failure)
		{
			if (required.test(source.relativize(file)) || !isPermissionRefusal(failure) || !Files.isReadable(file))
				return false;
			skipped.add(PortablePaths.text(source.relativize(file)));
			return true;
		}

		/**
		 * Leaves out a file or folder that could not be copied, if this copier goes on past failures.
		 *
		 * @param file    the file or folder of the session folder
		 * @param failure the failure that stopped its copy
		 * @throws IOException {@code failure} if this copier stops at failures or the file's copy is required
		 */
		private void leaveOut(Path file, IOException failure) throws IOException
		{
			if (!goesOnPastFailures || required.test(source.relativize(file)))
				throw failure;
			failures.add(failure(copyOf(file), failure));
		}

		@Override
		public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
		{
			Path relative = source.relativize(directory);
			if (relative.equals(Path.of(SESSION_HOME)) || UNCHANGED.contains(relative) || !includes(relative))
				return FileVisitResult.SKIP_SUBTREE;
			attempted = copyOf(directory);
			try
			{
				Files.createDirectories(attempted);
			}
			catch (IOException e)
			{
				if (relative.toString().isEmpty())
					throw e;
				if (!skips(directory, e))
					leaveOut(directory, e);
				return FileVisitResult.SKIP_SUBTREE;
			}
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
		{
			if (attributes.isRegularFile() && !UNCHANGED.contains(source.relativize(file)) &&
				includes(source.relativize(file)))
			{
				attempted = copyOf(file);
				try
				{
					Files.copy(file, attempted, NOFOLLOW_LINKS);
				}
				catch (IOException e)
				{
					if (!skips(file, e))
						leaveOut(file, e);
				}
			}
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException
		{
			attempted = copyOf(file);
			if (file.equals(source))
				throw failure;
			leaveOut(file, failure);
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
		{
			if (failure != null)
			{
				attempted = copyOf(directory);
				if (directory.equals(source))
					throw failure;
				leaveOut(directory, failure);
			}
			return FileVisitResult.CONTINUE;
		}
	}

	/**
	 * Prevents construction.
	 */
	private ResultsCopy()
	{
	}
}
