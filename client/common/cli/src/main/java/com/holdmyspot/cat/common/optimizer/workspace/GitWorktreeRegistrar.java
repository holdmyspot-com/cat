package com.holdmyspot.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.git.Git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The {@link WorktreeRegistrar} that runs Git itself, in place, with the file system access of the process that runs
 * it. Every Git operation runs through {@link Git}, in the project.
 * <p>
 * CAT writes the links of a moved worktree itself instead of running {@code git worktree repair}. That command also
 * rewrites the {@code .git} file of every other worktree that the repository registers, and in a copied project those
 * registrations name the original project's worktrees, which the command would then link to the copy.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class GitWorktreeRegistrar implements WorktreeRegistrar
{
	/**
	 * The text that precedes the registration's path in a worktree's {@code .git} file.
	 */
	private static final String GIT_FILE_PREFIX = "gitdir:";

	/**
	 * Creates the registrar.
	 */
	public GitWorktreeRegistrar()
	{
	}

	/**
	 * Returns where a campaign holds its optimization worktree.
	 *
	 * @param campaign the campaign directory
	 * @return the worktree's directory
	 * @throws NullPointerException if {@code campaign} is null
	 */
	public static Path worktreeOf(Path campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		return campaign.resolve("worktree");
	}

	/**
	 * Returns the common Git directory of a project's repository, which holds its configuration, its hooks and the
	 * registrations of its linked worktrees.
	 *
	 * @param project the project, which is inside its repository's work tree
	 * @return the directory's absolute path
	 * @throws NullPointerException if {@code project} is null
	 * @throws IOException          if Git fails
	 */
	public static Path commonDirectory(Path project) throws IOException
	{
		requireThat(project, "project").isNotNull();
		Git git = new Git(project.toAbsolutePath().normalize());
		return Path.of(git.run("rev-parse", "--path-format=absolute", "--git-common-dir").strip()).normalize();
	}

	/**
	 * Indicates whether a campaign's worktree and the project's repository name each other: the worktree's
	 * {@code .git} file names a registration of the repository, and that registration's {@code gitdir} file names the
	 * {@code .git} file. Reading the links changes nothing.
	 *
	 * @param project  the project, which is the top-level directory of its repository
	 * @param campaign the campaign directory
	 * @return {@code true} if the links are intact
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if Git fails or a link cannot be read
	 */
	public static boolean isLinked(Path project, Path campaign) throws IOException
	{
		requireThat(project, "project").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		Optional<Links> links = Links.read(project.toAbsolutePath().normalize(),
			campaign.toAbsolutePath().normalize());
		return links.isPresent() && links.get().isIntact();
	}

	@Override
	public void add(Path project, Path campaign) throws IOException
	{
		requireThat(project, "project").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		Path normalizedProject = project.toAbsolutePath().normalize();
		Git git = new Git(normalizedProject);
		// A worktree that sandboxed code deleted leaves a registration that makes Git refuse to add it again
		git.run("worktree", "prune");
		Files.createDirectories(commonDirectory(normalizedProject).resolve("worktrees"));
		git.run("worktree", "add", "--detach", worktreeOf(campaign.toAbsolutePath().normalize()).toString(), "HEAD");
	}

	@Override
	public void recreate(Path project, Path campaign) throws IOException
	{
		requireThat(project, "project").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		TemporaryDirectories.deleteRecursively(worktreeOf(campaign.toAbsolutePath().normalize()));
		add(project, campaign);
	}

	@Override
	public void remove(Path project, Path campaign) throws IOException
	{
		requireThat(project, "project").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		Git git = new Git(project.toAbsolutePath().normalize());
		Path worktree = worktreeOf(campaign.toAbsolutePath().normalize());
		// A worktree whose deletion was interrupted may have lost its ".git" file, which Git needs to remove it
		if (Files.exists(worktree.resolve(".git"), LinkOption.NOFOLLOW_LINKS) && repair(project, campaign))
			git.run("worktree", "remove", "--force", worktree.toString());
		TemporaryDirectories.deleteRecursively(worktree);
		git.run("worktree", "prune");
	}

	@Override
	public boolean repair(Path project, Path campaign) throws IOException
	{
		requireThat(project, "project").isNotNull();
		requireThat(campaign, "campaign").isNotNull();
		Path normalizedProject = project.toAbsolutePath().normalize();
		Optional<Links> read = Links.read(normalizedProject, campaign.toAbsolutePath().normalize());
		if (read.isEmpty())
			return false;
		Links links = read.get();
		if (links.isIntact())
			return true;
		if (!links.isRepairable(normalizedProject))
			return false;
		Files.writeString(links.dotGit(), GIT_FILE_PREFIX + " " + gitText(links.registration().toRealPath()) + "\n",
			UTF_8);
		Files.writeString(links.registration().resolve("gitdir"), gitText(links.dotGit().toRealPath()) + "\n", UTF_8);
		return true;
	}

	/**
	 * Returns a path as Git writes it in a link file, with {@code /} between names on every platform.
	 *
	 * @param path an absolute path
	 * @return the text
	 */
	private static String gitText(Path path)
	{
		return path.toString().replace(File.separatorChar, '/');
	}

	/**
	 * The links between a campaign's worktree and a registration of the project's repository.
	 *
	 * @param dotGit       the worktree's {@code .git} file
	 * @param named        the registration that the {@code .git} file names
	 * @param registration the registration of that name in the project's repository
	 * @param backlink     the {@code .git} file that the registration's {@code gitdir} file names, or empty if it names
	 *                     none
	 */
	private record Links(Path dotGit, Path named, Path registration, Optional<Path> backlink)
	{
		/**
		 * Reads the links of a campaign's worktree.
		 *
		 * @param project  the normalized absolute project
		 * @param campaign the normalized absolute campaign directory
		 * @return the links, or empty if the worktree's {@code .git} file names no registration that the project's
		 *   repository holds
		 * @throws IOException if Git fails or a link cannot be read
		 */
		static Optional<Links> read(Path project, Path campaign) throws IOException
		{
			Path worktree = worktreeOf(campaign);
			Path dotGit = worktree.resolve(".git");
			Optional<Path> named = linkedPath(dotGit, GIT_FILE_PREFIX, worktree);
			if (named.isEmpty())
				return Optional.empty();
			Path name = named.get().getFileName();
			if (name == null)
				return Optional.empty();
			// Git names a registration after the worktree's directory, and the copied registration keeps its name
			Path registration = commonDirectory(project).resolve("worktrees").resolve(name.toString());
			if (!Files.isRegularFile(registration.resolve("HEAD"), LinkOption.NOFOLLOW_LINKS))
				return Optional.empty();
			return Optional.of(new Links(dotGit, named.get(), registration, linkedPath(registration.resolve("gitdir"),
				"", registration)));
		}

		/**
		 * Indicates whether the {@code .git} file names the registration and the registration names the
		 * {@code .git} file.
		 *
		 * @return {@code true} if both links are intact
		 * @throws IOException if a file's identity cannot be read
		 */
		boolean isIntact() throws IOException
		{
			return backlink.isPresent() && isSameFile(named, registration) && isSameFile(backlink.get(), dotGit);
		}

		/**
		 * Indicates whether rewriting both links gives the worktree its own registration: the registration's
		 * {@code gitdir} names the {@code .git} file of the same campaign's worktree, below the same session, at
		 * another location of the project, and no other worktree's {@code .git} file there names the registration.
		 *
		 * @param project the normalized absolute project
		 * @return {@code true} if the links may be rewritten
		 * @throws IOException if a link cannot be read
		 */
		boolean isRepairable(Path project) throws IOException
		{
			if (backlink.isEmpty())
				return false;
			Path recorded = backlink.get();
			if (!isSameFile(recorded, dotGit) && isLinkedTo(recorded, registration))
			{
				// Another worktree of the project's repository uses the registration
				return false;
			}
			Path expected = dotGit;
			if (dotGit.startsWith(project))
				expected = project.relativize(dotGit);
			return recorded.endsWith(expected);
		}

		/**
		 * Reads the path that a link file of Git names.
		 *
		 * @param file      the link file
		 * @param prefix    the text that precedes the path
		 * @param directory the directory that a relative path is relative to
		 * @return the path, or empty if the file does not exist or names no path
		 * @throws IOException if the file cannot be read
		 */
		private static Optional<Path> linkedPath(Path file, String prefix, Path directory) throws IOException
		{
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
				return Optional.empty();
			String content = Files.readString(file, UTF_8);
			if (!content.startsWith(prefix))
				return Optional.empty();
			String path = content.substring(prefix.length()).strip();
			if (path.isEmpty())
				return Optional.empty();
			try
			{
				return Optional.of(directory.resolve(path).normalize());
			}
			catch (InvalidPathException _)
			{
				return Optional.empty();
			}
		}

		/**
		 * Indicates whether a {@code .git} file names a registration.
		 *
		 * @param dotGit       the {@code .git} file
		 * @param registration the registration's directory
		 * @return {@code true} if the file exists and names the registration
		 * @throws IOException if the file cannot be read
		 */
		private static boolean isLinkedTo(Path dotGit, Path registration) throws IOException
		{
			Path parent = dotGit.getParent();
			if (parent == null)
				return false;
			Optional<Path> named = linkedPath(dotGit, GIT_FILE_PREFIX, parent);
			return named.isPresent() && isSameFile(named.get(), registration);
		}

		/**
		 * Indicates whether two paths locate the same file.
		 *
		 * @param first  a path
		 * @param second another path
		 * @return {@code true} if both locate the same existing file, or the paths are equal
		 * @throws IOException if a file's identity cannot be read
		 */
		private static boolean isSameFile(Path first, Path second) throws IOException
		{
			try
			{
				return Files.isSameFile(first, second);
			}
			catch (NoSuchFileException _)
			{
				return false;
			}
		}
	}
}
