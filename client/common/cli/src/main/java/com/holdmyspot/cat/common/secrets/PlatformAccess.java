package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.Platform;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Exposes how a POSIX {@link Platform} searches the folders of a {@code PATH} to the module's tests.
 */
@FunctionalInterface
public interface PlatformAccess
{
	/**
	 * Finds a program by its name in the folders of a POSIX {@code PATH}, deciding through a supplied check which
	 * candidate is an executable file.
	 *
	 * @param program          a program's name or path
	 * @param environment      the environment of the process that starts the program
	 * @param isExecutableFile indicates whether a candidate path is an executable file
	 * @return the path of the first match, or {@code program} if nothing matches or the program names a path
	 * @throws NullPointerException if any of the arguments are null
	 */
	String findPosixProgram(String program, Map<String, String> environment, Predicate<Path> isExecutableFile);
}
