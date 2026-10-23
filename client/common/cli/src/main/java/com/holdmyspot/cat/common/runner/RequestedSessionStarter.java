package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Starts a requested runner using a harness-specific artifact resolver. The broker owns the returned process,
 * its input, and its termination. Implementations must be thread-safe.
 */
@FunctionalInterface
public interface RequestedSessionStarter
{
	/**
	 * Resolves the selected harness's runner and starts it with the supplied options, without invoking a shell.
	 *
	 * @param harness   the requested harness identifier
	 * @param arguments runner options, including retained output and receipt destinations
	 * @param directory the private response directory for process diagnostics
	 * @return the child runner process
	 * @throws IllegalArgumentException if the harness is unsupported
	 * @throws IOException              if resolution or process startup fails
	 */
	Process start(String harness, List<String> arguments, Path directory) throws IOException;
}
