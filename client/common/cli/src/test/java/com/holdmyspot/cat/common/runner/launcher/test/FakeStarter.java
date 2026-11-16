package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.fakes.test.FakeProcessLauncher;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.contract.test.RequestedSessionStarterContract.StartedRunner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Starts supported requests with the supplied arguments and directory and refuses unsupported harnesses.
 */
public final class FakeStarter implements RequestedSessionStarter
{
	/**
	 * The identifier of the only harness whose runner the starter starts.
	 */
	public static final String HARNESS = "claude";
	private final FakeProcessLauncher launcher = new FakeProcessLauncher();

	/**
	 * Creates a starter whose runners exit with status {@code 0}.
	 */
	public FakeStarter()
	{
	}

	@Override
	public Process start(String harness, List<String> arguments, Path directory) throws IOException
	{
		if (!harness.equals(HARNESS))
			throw new IllegalArgumentException("Unsupported requested harness: " + harness);
		ProcessBuilder builder = new ProcessBuilder(arguments);
		builder.directory(directory.toFile());
		builder.redirectError(directory.resolve(ERROR_FILE).toFile());
		return launcher.start(builder);
	}

	/**
	 * Returns immutable snapshots of the supported requests started so far.
	 *
	 * @return the requests, in start order
	 */
	public List<StartedRunner> started()
	{
		return launcher.started().stream().map(builder -> new StartedRunner(HARNESS, List.copyOf(builder.command()),
			builder.directory().toPath(), builder.redirectError().file().toPath())).toList();
	}
}
