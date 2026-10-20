package com.holdmyspot.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The {@link Forks} that fork a Git repository.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class GitForks implements Forks
{
	private final FileReplacer replacer;

	/**
	 * Creates the forks.
	 *
	 * @param replacer replaces each fork's stored participant selection
	 * @throws NullPointerException if {@code replacer} is null
	 */
	public GitForks(FileReplacer replacer)
	{
		requireThat(replacer, "replacer").isNotNull();
		this.replacer = replacer;
	}

	@Override
	public ForkView create(Path base, Path campaign, Set<Path> participants) throws IOException
	{
		return Fork.create(base, campaign, participants, replacer);
	}

	@Override
	public Set<Path> tracked(Path base) throws IOException
	{
		return Fork.tracked(base);
	}

	@Override
	public Set<Path> trackedLinks(Path base) throws IOException
	{
		return Fork.trackedLinks(base);
	}

	@Override
	public String diff(Path base, List<Path> paths) throws IOException
	{
		return Fork.diff(base, paths);
	}

	@Override
	public ForkView open(Path base, Path campaign) throws IOException
	{
		return Fork.open(base, campaign, replacer);
	}

	@Override
	public boolean exists(Path campaign)
	{
		return Files.isDirectory(Fork.worktreeOf(campaign));
	}

	@Override
	public void remove(Path base, Path campaign) throws IOException
	{
		Fork.remove(base, campaign);
	}
}
