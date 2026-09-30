package io.github.cowwoc.cat.common.optimizer.state;

import java.io.IOException;
import java.nio.file.Path;

/** Canonicalizes campaign paths embedded in evidence-bearing lifecycle capabilities. */
final class ActivationPath
{
	/** Prevents construction. */
	private ActivationPath()
	{
	}

	/**
	 * Resolves an existing campaign directory without retaining a symlink alias.
	 *
	 * @param directory campaign directory
	 * @return canonical real path
	 */
	static Path canonical(Path directory)
	{
		if (directory == null)
		{
			throw new NullPointerException("directory");
		}
		try
		{
			return directory.toRealPath();
		}
		catch (IOException exception)
		{
			throw new IllegalArgumentException("Campaign directory cannot be canonicalized: " + directory,
				exception);
		}
	}
}
