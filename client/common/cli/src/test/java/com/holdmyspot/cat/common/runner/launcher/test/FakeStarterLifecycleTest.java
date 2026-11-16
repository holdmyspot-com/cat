package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.test.ProcessLifecycleContract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks that {@link FakeStarter} processes and their handles share the lifecycle required of a started process.
 */
public final class FakeStarterLifecycleTest extends ProcessLifecycleContract
{
	/**
	 * Creates the fake-starter lifecycle checks.
	 */
	public FakeStarterLifecycleTest()
	{
	}

	@Override
	protected Fixture start() throws IOException
	{
		return new Started();
	}

	/**
	 * Owns the fake process and request directory used for one lifecycle check.
	 */
	private static final class Started implements Fixture
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final Process process;
		private final List<ProcessHandle> descendants = new ArrayList<>();

		/**
		 * Starts a request through {@link FakeStarter} and owns it through cleanup.
		 *
		 * @throws IOException if the request directory cannot be created
		 */
		private Started() throws IOException
		{
			Path directory;
			try (TestJvmScope scope = new TestJvmScope())
			{
				directory = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "fake-starter-"));
			}
			catch (IOException | RuntimeException | Error failure)
			{
				try (cleanup)
				{
					throw failure;
				}
			}
			try
			{
				this.process = new FakeStarter().start(FakeStarter.HARNESS, List.of(), directory);
			}
			catch (IOException | RuntimeException | Error failure)
			{
				try (cleanup)
				{
					throw failure;
				}
			}
			try
			{
				descendants.addAll(process.toHandle().descendants().toList());
				descendants.forEach(ProcessHandle::destroyForcibly);
			}
			catch (RuntimeException | Error failure)
			{
				try (cleanup;
					process)
				{
					process.destroyForcibly();
					descendants.forEach(ProcessHandle::destroyForcibly);
					throw failure;
				}
			}
		}

		@Override
		public Process process()
		{
			return process;
		}

		@Override
		public void close() throws IOException
		{
			try (cleanup;
				process)
			{
				process.destroyForcibly();
				descendants.forEach(ProcessHandle::destroyForcibly);
			}
		}
	}
}
