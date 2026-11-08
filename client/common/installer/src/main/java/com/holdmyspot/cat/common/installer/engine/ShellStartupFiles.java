package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The shell startup files in a home folder, which install makes read-only to a harness's sessions. Each harness runs
 * CAT's request server outside its sandbox when a command names it by its absolute path, after it strips a few
 * wrapper words such as {@code nohup}. A session that could write a startup file could define a function under such a
 * wrapper's name there, and the shell that runs the next such command would load it and run the session's own code
 * outside the sandbox.
 */
public final class ShellStartupFiles
{
	/**
	 * The names of the startup files that Bash and Zsh read from the home folder.
	 */
	private static final List<String> NAMES = List.of(".bashrc", ".bash_profile", ".bash_login", ".bash_logout",
		".profile", ".zshrc", ".zshenv", ".zprofile", ".zlogin");

	/**
	 * Prevents instantiation.
	 */
	private ShellStartupFiles()
	{
	}

	/**
	 * Returns the startup files in a home folder, whether or not they exist.
	 *
	 * @param userHome the absolute home folder
	 * @return the files, in a stable order
	 * @throws NullPointerException     if {@code userHome} is null
	 * @throws IllegalArgumentException if {@code userHome} is relative
	 */
	public static List<Path> in(Path userHome)
	{
		requireThat(userHome, "userHome").isAbsolute();
		Path home = userHome.normalize();
		List<Path> files = new ArrayList<>();
		for (String name : NAMES)
			files.add(home.resolve(name));
		return List.copyOf(files);
	}
}
