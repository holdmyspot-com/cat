package io.github.cowwoc.cat.common.optimizer.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * The {@link Forks} that fork a Git repository.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class GitForks implements Forks
{
	/**
	 * Creates the forks.
	 */
	public GitForks()
	{
	}

	@Override
	public ForkView create(Path base, Path campaign, Set<Path> participants) throws IOException
	{
		return Fork.create(base, campaign, participants);
	}

	@Override
	public Set<Path> tracked(Path base) throws IOException
	{
		return Fork.tracked(base);
	}

	@Override
	public ForkView open(Path base, Path campaign) throws IOException
	{
		return Fork.open(base, campaign);
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
