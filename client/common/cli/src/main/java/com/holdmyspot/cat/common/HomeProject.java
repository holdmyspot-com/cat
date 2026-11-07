package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Decides whether a folder may be a CAT project. The user's home folder never may: a project keeps its CAT session
 * directories in the folder that {@link SessionDirectory#ROOT} names, and in the home folder that folder holds the
 * session launcher's state, which sandboxed commands are denied writing. Every other folder may, including the folders
 * below the home folder and the folders that contain it.
 */
public final class HomeProject
{
	/**
	 * Prevents instantiation.
	 */
	private HomeProject()
	{
	}

	/**
	 * Returns why a folder cannot be a CAT project. The folder and the home folder are compared by their real paths,
	 * so a symbolic link to the home folder is the home folder. A folder whose real path cannot be resolved, such as
	 * one that does not exist, is compared by its absolute normalized path.
	 *
	 * @param project the folder
	 * @param home    the user's home folder
	 * @return a message that names the home folder and tells the user to open a folder below it, if {@code project}
	 *   is the home folder; otherwise empty
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static Optional<String> refusal(Path project, Path home)
	{
		requireThat(project, "project").isNotNull();
		requireThat(home, "home").isNotNull();
		if (!realPath(project).equals(realPath(home)))
			return Optional.empty();
		return Optional.of("The home folder " + home + " cannot be a CAT project. Open a folder below it instead.");
	}

	/**
	 * Returns the real path of a folder.
	 *
	 * @param folder the folder
	 * @return its real path, or its absolute normalized path if the real path cannot be resolved
	 */
	private static Path realPath(Path folder)
	{
		try
		{
			return folder.toRealPath();
		}
		catch (IOException _)
		{
			return folder.toAbsolutePath().normalize();
		}
	}
}
