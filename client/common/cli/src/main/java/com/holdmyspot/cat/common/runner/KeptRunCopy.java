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
 * Copies a kept run to where its requester reads it, leaving out links, the child's isolated home, which holds its
 * logins, and the project files that the child's sandbox kept it from changing.
 */
public final class KeptRunCopy
{
	/**
	 * The directory of a kept run that holds the child's isolated home, which is never copied.
	 */
	private static final String RUN_HOME = "home";
	/**
	 * The directory of a kept run that holds the copy of the project that the child worked in.
	 */
	private static final String RUN_PROJECT = "project";
	/**
	 * The directory of a kept run that holds the copy of the project as it was before the child's first turn.
	 */
	private static final String RUN_INITIAL_PROJECT = "initial-project";
	/**
	 * The directories of a run that {@link #copyProjects(Path, Path)} copies.
	 */
	private static final Set<String> PROJECTS = Set.of(RUN_PROJECT, RUN_INITIAL_PROJECT);

	/**
	 * How the copy of a kept run ended.
	 *
	 * @param incomplete the message that names the file that could not be copied and says that the kept run is
	 *                   incomplete, or empty if the run was copied in full, apart from what the copier's sandbox
	 *                   protects
	 * @param skipped    the paths, relative to the run, that the copier's sandbox refused to let the copy write
	 */
	public record Result(Optional<String> incomplete, List<String> skipped)
	{
		/**
		 * Creates a result.
		 *
		 * @param incomplete the message that names the file that could not be copied, or empty
		 * @param skipped    the paths, relative to the run, that the copy skipped
		 * @throws NullPointerException if {@code incomplete} or {@code skipped} are null
		 */
		public Result
		{
			requireThat(incomplete, "incomplete").isNotNull();
			skipped = List.copyOf(skipped);
		}
	}

	/**
	 * Copies a kept run into {@code target}, leaving out links, the child's isolated home and the project files that
	 * the child's sandbox kept it from changing. A file or folder that the copier's sandbox refuses to write is skipped
	 * without a message and listed in the result. Any other file that cannot be written ends the copy and is reported,
	 * not thrown, so that it does not hide the session's outcome.
	 *
	 * @param source the kept run
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
	 * Replaces the project and the initial project of a run with those of another run, which the requester reads
	 * while the other run's session is still working. The copy leaves out what {@link #copy(Path, Path)} leaves out.
	 * A run that holds neither leaves the target without them.
	 *
	 * @param source the run whose project and initial project are copied
	 * @param target the run that receives them, which need not exist
	 * @throws NullPointerException if {@code source} or {@code target} are null
	 * @throws IOException          if the target's earlier copy cannot be removed, or a file cannot be copied
	 */
	public static void copyProjects(Path source, Path target) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(target, "target").isNotNull();
		deleteProjects(target);
		Optional<String> incomplete = copy(source, target, PROJECTS::contains).incomplete();
		if (incomplete.isPresent())
			throw new IOException(incomplete.get());
	}

	/**
	 * Removes the project and the initial project of a run's copy, so that a copy of the whole run can follow.
	 *
	 * @param run the run's copy
	 * @throws NullPointerException if {@code run} is null
	 * @throws IOException          if a file cannot be removed
	 */
	public static void deleteProjects(Path run) throws IOException
	{
		requireThat(run, "run").isNotNull();
		for (String name : PROJECTS)
			TemporaryDirectories.deleteRecursively(run.resolve(name));
	}

	/**
	 * Copies the directories of a kept run that a filter accepts.
	 *
	 * @param source   the kept run
	 * @param target   the copy, which need not exist
	 * @param included accepts the name of each directory directly below the run that the copy includes
	 * @return how the copy ended
	 */
	private static Result copy(Path source, Path target, Predicate<String> included)
	{
		RunCopier copier = new RunCopier(source, target, included);
		try
		{
			Files.walkFileTree(source, copier);
		}
		catch (FileSystemException e)
		{
			String reason = e.getReason();
			if (reason == null)
				reason = e.getClass().getSimpleName();
			return new Result(Optional.of(incompleteRun(copier.attempted, reason, target)), copier.skipped);
		}
		catch (IOException e)
		{
			return new Result(Optional.of(incompleteRun(copier.attempted, e.getClass().getSimpleName(), target)),
				copier.skipped);
		}
		return new Result(Optional.empty(), copier.skipped);
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
	 * Returns the message that says a kept run is incomplete.
	 *
	 * @param file   the file that could not be copied
	 * @param reason why it could not be copied
	 * @param target the copy of the kept run
	 * @return the message
	 */
	private static String incompleteRun(Path file, String reason, Path target)
	{
		return "The file " + file + " could not be copied: " + reason + ". The kept run at " + target +
			" is incomplete.";
	}

	/**
	 * Copies a kept run's directories and regular files, skips what the copier's sandbox refuses to write, and
	 * remembers which copy it attempted last.
	 * <p>
	 * <b>Thread Safety:</b> This class is not thread-safe.
	 */
	private static final class RunCopier extends SimpleFileVisitor<Path>
	{
		/**
		 * The files and folders of the run, relative to it, that the child's sandbox kept it from changing, so that
		 * they equal the initial project's and nothing reads them from a kept run. The copy leaves them out because
		 * the requester's own sandbox can refuse to write them, as Claude Code's does for its settings and a
		 * project's Git configuration and hooks, and a child is never allowed more than its parent: it would
		 * otherwise change a file that the parent cannot. A copier outside that sandbox must not write them where the
		 * requester reads the run either, which would let the child write where the requester's sandbox forbids it.
		 */
		private static final Set<Path> UNCHANGED = ProtectedProjectFiles.paths().stream().
			map(path -> Path.of(RUN_PROJECT).resolve(path)).collect(Collectors.toUnmodifiableSet());
		/**
		 * The kept run that is copied.
		 */
		private final Path source;
		/**
		 * The copy of the kept run.
		 */
		private final Path target;
		/**
		 * Accepts the name of each directory directly below the run that the copy includes.
		 */
		private final Predicate<String> included;
		/**
		 * The paths, relative to the run, that the copier's sandbox refused to let the copy write.
		 */
		private final List<String> skipped = new ArrayList<>();
		/**
		 * The file or directory of the copy that the visitor last tried to write.
		 */
		private Path attempted;

		/**
		 * Creates a copier.
		 *
		 * @param source the kept run that is copied
		 * @param target the copy of the kept run
		 * @param included accepts the name of each directory directly below the run that the copy includes
		 */
		RunCopier(Path source, Path target, Predicate<String> included)
		{
			this.source = source;
			this.target = target;
			this.included = included;
			this.attempted = target;
		}

		/**
		 * Indicates whether the copy includes a path of the kept run.
		 *
		 * @param relative the path, relative to the run
		 * @return {@code true} for the run itself and for every path whose first name the filter accepts
		 */
		private boolean includes(Path relative)
		{
			return relative.toString().isEmpty() || included.test(relative.getName(0).toString());
		}

		/**
		 * Returns where a file of the kept run goes in the copy.
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
		 * it, and if so, remembers that the copy skips it. A refusal to read the kept run's file is not one.
		 *
		 * @param file    the file or folder that is copied
		 * @param failure the failure
		 * @return {@code true} if the copy skips the file or folder
		 */
		private boolean skips(Path file, IOException failure)
		{
			if (!isPermissionRefusal(failure) || !Files.isReadable(file))
				return false;
			skipped.add(PortablePaths.text(source.relativize(file)));
			return true;
		}

		@Override
		public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException
		{
			Path relative = source.relativize(directory);
			if (relative.equals(Path.of(RUN_HOME)) || UNCHANGED.contains(relative) || !includes(relative))
				return FileVisitResult.SKIP_SUBTREE;
			attempted = copyOf(directory);
			try
			{
				Files.createDirectories(attempted);
			}
			catch (IOException e)
			{
				if (relative.toString().isEmpty() || !skips(directory, e))
					throw e;
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
						throw e;
				}
			}
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException
		{
			attempted = copyOf(file);
			throw failure;
		}
	}

	/**
	 * Prevents construction.
	 */
	private KeptRunCopy()
	{
	}
}
