package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.workspace.GitWorktreeRegistrar;
import com.holdmyspot.cat.common.optimizer.workspace.WorktreeRegistrar;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;

/**
 * Carries out a worktree request outside the requesting session's sandbox, after the checks that keep the session
 * from pointing the operation at anything but its own campaign's worktree, or from choosing a program that Git would
 * run. A request server and a nested session's runner serve requests through it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class WorktreeService
{
	private final WorktreeRegistrar registrar;

	/**
	 * Creates a service.
	 *
	 * @param registrar runs the operations in place
	 * @throws NullPointerException if {@code registrar} is null
	 */
	WorktreeService(WorktreeRegistrar registrar)
	{
		requireThat(registrar, "registrar").isNotNull();
		this.registrar = registrar;
	}

	/**
	 * Returns the reason that a campaign is not the requesting session's.
	 *
	 * @param campaign the campaign's identifier
	 * @return the reason
	 */
	static String foreignCampaign(String campaign)
	{
		return "Campaign " + campaign + " is not this session's: the worktree request names no campaign directory " +
			"of the session, or a link stands in the way of the campaign's worktree.";
	}

	/**
	 * Checks and carries out a request.
	 *
	 * @param request   the request
	 * @param project   the project, which is the top-level directory of its repository
	 * @param campaigns the directory that holds the requesting session's campaigns, inside {@code project}
	 * @param policy    the requesting session's policy, or empty if its commands cannot write the repository's
	 *                  configuration or hooks by construction
	 * @return the response
	 * @throws NullPointerException if any of the arguments are null
	 */
	WorktreeResponse serve(WorktreeRequest request, Path project, Path campaigns, Optional<ParentPolicy> policy)
	{
		requireThat(request, "request").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(campaigns, "campaigns").isNotNull();
		requireThat(policy, "policy").isNotNull();
		Path normalizedProject = project.toAbsolutePath().normalize();
		Path campaign = campaigns.toAbsolutePath().normalize().resolve(request.campaign());
		Path worktree = GitWorktreeRegistrar.worktreeOf(campaign);
		try
		{
			Optional<String> refusal = refusal(request, normalizedProject, campaign, worktree, policy);
			if (refusal.isPresent())
				return WorktreeResponse.refused(refusal.get());
			return switch (request.operation())
			{
				case ADD ->
				{
					registrar.add(normalizedProject, campaign);
					yield WorktreeResponse.done();
				}
				case RECREATE ->
				{
					registrar.recreate(normalizedProject, campaign);
					yield WorktreeResponse.done();
				}
				case REMOVE ->
				{
					registrar.remove(normalizedProject, campaign);
					yield WorktreeResponse.done();
				}
				case REPAIR -> WorktreeResponse.repaired(registrar.repair(normalizedProject, campaign));
			};
		}
		catch (IOException e)
		{
			String message = e.getMessage();
			if (message == null)
				message = e.getClass().getSimpleName();
			return WorktreeResponse.failed(message);
		}
	}

	/**
	 * Returns why a request is refused before it changes anything.
	 *
	 * @param request  the request
	 * @param project  the normalized absolute project
	 * @param campaign the normalized absolute campaign directory
	 * @param worktree the campaign's worktree
	 * @param policy   the requesting session's policy, or empty if it needs no check
	 * @return the reason, or empty if the request may be carried out
	 * @throws IOException if an entry or the policy cannot be read
	 */
	private static Optional<String> refusal(WorktreeRequest request, Path project, Path campaign, Path worktree,
		Optional<ParentPolicy> policy) throws IOException
	{
		if (!worktree.startsWith(project))
			return Optional.of(foreignCampaign(request.campaign()));
		// The session's commands can write these entries, and a link among them could point a removal at any folder
		Path relative = project.relativize(worktree);
		Path entry = project;
		for (Path name : relative)
		{
			entry = entry.resolve(name);
			if (isLink(entry))
				return Optional.of(foreignCampaign(request.campaign()));
		}
		if (!Files.isDirectory(campaign, LinkOption.NOFOLLOW_LINKS))
			return Optional.of(foreignCampaign(request.campaign()));
		if (request.operation() == WorktreeRequest.Operation.ADD && Files.exists(worktree, LinkOption.NOFOLLOW_LINKS))
		{
			return Optional.of("The worktree of campaign " + request.campaign() + " already exists, so it cannot be " +
				"added: " + worktree);
		}
		if (policy.isPresent() && policy.get().sandboxed())
		{
			Path common = GitWorktreeRegistrar.commonDirectory(project);
			for (Path path : List.of(common.resolve("config"), common.resolve("hooks")))
			{
				if (policy.get().mayWrite(path))
				{
					return Optional.of("The session's sandbox lets its commands write " + path + ", which Git reads " +
						"on every operation and which can name a program for Git to run, so CAT does not run Git " +
						"outside the sandbox for it. Deny the session's commands writes to " + path + ", then run " +
						"the command again.");
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Indicates whether an entry is a symbolic link or another kind of link, such as a Windows junction.
	 *
	 * @param entry the entry
	 * @return {@code true} if the entry is a link; {@code false} if it is a file or a directory or does not exist
	 * @throws IOException if the entry's attributes cannot be read
	 */
	private static boolean isLink(Path entry) throws IOException
	{
		BasicFileAttributes attributes;
		try
		{
			attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (NoSuchFileException _)
		{
			return false;
		}
		return attributes.isSymbolicLink() || attributes.isOther();
	}
}
