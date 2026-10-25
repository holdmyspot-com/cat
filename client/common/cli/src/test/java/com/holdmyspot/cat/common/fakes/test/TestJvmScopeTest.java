package com.holdmyspot.cat.common.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.AbstractJvmScope;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.ExclusiveFileLock;
import java.nio.channels.FileChannel;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
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

	/**
	 * Repeated service access and child scopes share one reservation until the held lease closes. A controlled
	 * refusal before opening a second channel makes missing coordination observable without releasing the first lock.
	 *
	 * @throws IOException if the owned directory or file lock cannot be used
	 */
	@Test
	public void sharesFileLockReservations() throws IOException
	{
		// Requirement: R-T1
		try (TestJvmScope parent = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories();
			AbstractJvmScope child = new AbstractJvmScope(parent)
			{
			})
		{
			Path directory = cleanup.add(Files.createTempDirectory(parent.temporaryDirectory(), "scope-locks-"));
			Path file = directory.resolve("owned.lock");
			try (ExclusiveFileLock held = parent.fileLocks().tryLock(file,
				path -> FileChannel.open(path, CREATE, WRITE)).orElseThrow())
			{
				requireThat(parent.fileLocks().tryLock(file, _ ->
				{
					throw new IOException("Repeated service access opened a held file");
				}), "parentReservation").isEmpty();
				requireThat(child.fileLocks().tryLock(file, _ ->
				{
					throw new IOException("Child scope opened a held file");
				}), "childReservation").isEmpty();
				requireThat(held.isValid(), "held").isTrue();
			}
			try (ExclusiveFileLock reacquired = child.fileLocks().tryLock(file,
				path -> FileChannel.open(path, CREATE, WRITE)).orElseThrow())
			{
				requireThat(reacquired.isValid(), "reacquired").isTrue();
			}
		}
	}

	/**
	 * A closed scope refuses access to its file-lock coordinator.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void rejectsFileLockAccessAfterClose()
	{
		// Requirement: R-T1
		TestJvmScope scope = new TestJvmScope();
		try (scope)
		{
		}
		scope.fileLocks();
	}
}
