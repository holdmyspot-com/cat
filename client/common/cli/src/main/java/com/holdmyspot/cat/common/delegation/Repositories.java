package com.holdmyspot.cat.common.delegation;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.git.Git;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Creates independently mutable repositories from a frozen source snapshot.
 */
public final class Repositories
{
	/**
	 * Prevents construction of the repository factory.
	 */
	private Repositories()
	{
	}

	/**
	 * Creates the cohort repository and one clone per work unit.
	 *
	 * @param cohort cohort-owned directory
	 * @param snapshot immutable source snapshot
	 * @param workIds requested work identifiers
	 * @param environment complete Git environment
	 * @param launcher Git process launcher
	 * @return repository and work project paths
	 * @throws IOException if repository creation fails
	 */
	public static RepositorySet create(Path cohort, SourceSnapshot snapshot, List<String> workIds,
		Map<String, String> environment, ExecutableFiles.ProcessLauncher launcher) throws IOException
	{
		Set<String> uniqueWorkIds = new HashSet<>(workIds);
		if (uniqueWorkIds.size() != workIds.size() || workIds.isEmpty())
			throw new IllegalArgumentException("Work identifiers must be nonempty and unique");
		for (String workId : workIds)
			if (!workId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
				throw new IllegalArgumentException("Unsafe work identifier: " + workId);
		Path gitHome = cohort.resolve("git-home");
		Files.createDirectories(gitHome);
		Files.writeString(gitHome.resolve("gitconfig"), "");
		Map<String, String> gitEnvironment = safeEnvironment(cohort, environment);
		Path sourceRepository = cohort.resolve("source/repository");
		Files.createDirectories(sourceRepository);
		Git sourceGit = new Git(sourceRepository, gitEnvironment, launcher);
		sourceGit.run("init", "--quiet", "--initial-branch=main");
		configureRepository(sourceRepository, sourceGit);
		for (SourceSnapshot.Entry entry : snapshot.entries())
		{
			if (!entry.present() || entry.kind().equals("directory"))
				continue;
			String mode;
			if (entry.kind().equals("symlink"))
				mode = "120000";
			else if (entry.executable())
				mode = "100755";
			else
				mode = "100644";
			String blob = sourceGit.run("hash-object", "--no-filters", "-w", "--",
				snapshot.objectPath(entry).toString()).strip();
			sourceGit.run("update-index", "--add", "--cacheinfo", mode + "," + blob + "," + entry.path());
		}
		String snapshotTree = sourceGit.run("write-tree").strip();
		String snapshotCommit = sourceGit.run("commit-tree", snapshotTree, "-m", "CAT delegation source snapshot").strip();
		sourceGit.run("update-ref", "refs/heads/snapshot", snapshotCommit);
		Map<String, Path> workProjects = new HashMap<>();
		for (String workId : workIds)
		{
			Path workProject = cohort.resolve("work").resolve(workId).resolve("project");
			Files.createDirectories(workProject.getParent());
			new Git(sourceRepository, gitEnvironment, launcher).run("clone", "--quiet", "--shared", "--no-checkout",
				sourceRepository.toString(), workProject.toString());
			Git workGit = new Git(workProject, gitEnvironment, launcher);
			configureRepository(workProject, workGit);
			workGit.run("checkout", "--quiet", "--detach", snapshotCommit);
			snapshot.materializeReplacing(workProject);
			workProjects.put(workId, workProject);
		}
		return new RepositorySet(sourceRepository, workProjects, snapshotCommit, snapshotTree);
	}

	/**
	 * Builds a Git environment that excludes caller Git configuration injection.
	 *
	 * @param cohort cohort-owned root
	 * @param inherited caller environment
	 * @return complete isolated Git environment
	 */
	private static Map<String, String> safeEnvironment(Path cohort, Map<String, String> inherited)
	{
		Map<String, String> result = new HashMap<>();
		for (String name : List.of("PATH", "SYSTEMROOT"))
		{
			String value = inherited.get(name);
			if (value != null)
				result.put(name, value);
		}
		Path home = cohort.resolve("git-home");
		result.put("HOME", home.toString());
		result.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
		result.put("GIT_CONFIG_NOSYSTEM", "1");
		result.put("GIT_CONFIG_GLOBAL", home.resolve("gitconfig").toString());
		result.put("GIT_TERMINAL_PROMPT", "0");
		result.put("GIT_OPTIONAL_LOCKS", "0");
		result.put("GIT_AUTHOR_NAME", "CAT delegation");
		result.put("GIT_AUTHOR_EMAIL", "delegation@localhost");
		result.put("GIT_COMMITTER_NAME", "CAT delegation");
		result.put("GIT_COMMITTER_EMAIL", "delegation@localhost");
		return Map.copyOf(result);
	}

	/**
	 * Disables transformations and hooks in an operation-owned repository.
	 *
	 * @param repository repository root
	 * @param git Git access for that root
	 * @throws IOException if repository settings cannot be written
	 */
	private static void configureRepository(Path repository, Git git) throws IOException
	{
		Path hooks = repository.resolve(".git/cat-disabled-hooks");
		Files.createDirectories(hooks);
		git.run("config", "core.hooksPath", hooks.toString());
		git.run("config", "core.autocrlf", "false");
		git.run("config", "core.filemode", "true");
		git.run("config", "core.fsmonitor", "false");
		git.run("config", "maintenance.auto", "false");
		git.run("config", "commit.gpgsign", "false");
	}

	/**
	 * Repository paths and immutable baseline identities for a cohort.
	 *
	 * @param sourceRepository cohort-owned snapshot repository
	 * @param workProjects delegate project roots keyed by work identifier
	 * @param snapshotCommit source snapshot commit
	 * @param snapshotTree source snapshot tree
	 */
	public record RepositorySet(Path sourceRepository, Map<String, Path> workProjects, String snapshotCommit,
		String snapshotTree)
	{
		/**
		 * Copies the work project map into the immutable result.
		 *
		 * @param sourceRepository cohort-owned snapshot repository
		 * @param workProjects delegate project roots keyed by work identifier
		 * @param snapshotCommit source snapshot commit
		 * @param snapshotTree source snapshot tree
		 */
		public RepositorySet
		{
			workProjects = Map.copyOf(workProjects);
		}
	}
}
