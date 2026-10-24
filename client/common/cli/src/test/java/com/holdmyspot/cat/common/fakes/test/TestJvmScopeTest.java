package com.holdmyspot.cat.common.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.AbstractJvmScope;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.annotations.Test;

/**
 * Tests {@link TestJvmScope}.
 */
public final class TestJvmScopeTest
{
	/**
	 * Verifies the scope creates its normalized test temporary directory.
	 */
	@Test
	public void createsTestTemporaryDirectory() throws IOException
	{
		try (TestJvmScope build = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(build.temporaryDirectory(), "test-jvm-"));
			Path target = root.resolve("created");
			try (TestJvmScope scope = new TestJvmScope(target.resolve("../created")))
			{
				requireThat(scope.temporaryDirectory(), "temporaryDirectory").isEqualTo(target);
				requireThat(target, "target").isDirectory();
			}
		}
	}

	/**
	 * Verifies closed scopes refuse temporary-directory access.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void rejectsAccessAfterClose() throws IOException
	{
		TestJvmScope scope = new TestJvmScope();
		try (scope)
		{
		}
		scope.temporaryDirectory();
	}

	/**
	 * A child scope shares its parent's controlled executable runner, including recovery from a busy file without
	 * sleeping or starting an external program.
	 *
	 * @throws IOException if the controlled process request fails
	 */
	@Test
	public void sharesControlledExecutableRunner() throws IOException
	{
		try (TestJvmScope build = new TestJvmScope();
			FakeHarnessCli process = new FakeHarnessCli(_ -> {}))
		{
			ManualClock clock = new ManualClock(Instant.EPOCH);
			AtomicBoolean busy = new AtomicBoolean(true);
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(
				() -> clock.advance(Duration.ofMillis(25)), clock, _ ->
				{
					if (busy.getAndSet(false))
						throw new IOException("ETXTBSY: controlled busy executable");
					return process;
				});
			try (TestJvmScope parent = new TestJvmScope(build.temporaryDirectory(), executables);
				AbstractJvmScope child = new AbstractJvmScope(parent)
				{
				};
				Process result = child.executableFiles().run(new ProcessBuilder("controlled-executable")))
			{
				requireThat(result, "process").isEqualTo(process);
				requireThat(child.executableFiles(), "childExecutables").isEqualTo(parent.executableFiles());
			}
		}
	}

	/**
	 * A closed scope refuses executable-file service access.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void rejectsExecutableAccessAfterClose()
	{
		TestJvmScope scope = new TestJvmScope();
		try (scope)
		{
		}
		scope.executableFiles();
	}
}
