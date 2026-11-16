package com.holdmyspot.cat.codex.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.ExecutableFiles.ProcessLauncher;
import com.holdmyspot.cat.codex.runner.CodexCuratedPluginCache;
import com.holdmyspot.cat.codex.secrets.CodexCuratedPluginCacheAccess;
import com.holdmyspot.cat.codex.secrets.CodexCuratedPluginCacheAccess.Cleanup;
import com.holdmyspot.cat.codex.secrets.CodexCuratedPluginCacheSecrets;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import com.holdmyspot.cat.common.test.ExitedProcess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

/**
 * Tests {@link CodexCuratedPluginCache}.
 */
public final class CodexCuratedPluginCacheTest
{
	/**
	 * The verified parent revision supplied to every cache-seeding fixture.
	 */
	private static final String REVISION = "442228db01aedfd27c5fffbe57fb7e2ad6dc8117";

	/**
	 * Verifies the child gets its own working tree and metadata over the parent's objects, without a checkout.
	 */
	@Test
	public void clonesWithOwnWorkingTree()
	{
		Path parent = Path.of("parent", ".tmp", "plugins");
		Path child = Path.of("child", ".tmp", "plugins");

		requireThat(CodexCuratedPluginCache.cloneCommand(parent, child), "cloneCommand").isEqualTo(List.of("git",
			"clone", "--quiet", "--shared", "--no-checkout", parent.toString(), child.toString()));
	}

	/**
	 * Verifies the child checks out exactly the parent's verified revision, detached from any branch.
	 */
	@Test
	public void checksOutVerifiedRevision()
	{
		String revision = "442228db01aedfd27c5fffbe57fb7e2ad6dc8117";

		requireThat(CodexCuratedPluginCache.checkoutCommand(revision), "checkoutCommand").
			isEqualTo(List.of("git", "checkout", "--quiet", "--detach", revision));
	}

	/**
	 * Verifies an incomplete parent cache leaves the child home untouched so Codex can perform its ordinary startup
	 * synchronization.
	 *
	 * @throws IOException if fixture setup or cache seeding fails
	 */
	@Test
	public void skipsUnverifiedSnapshot() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				jvm.temporaryDirectory(),
				"cat-curated-plugin-cache-"));
			Path parentHome = root.resolve("parent");
			Path parentTemporaryDirectory = Files.createDirectories(parentHome.resolve(".tmp"));
			Files.createDirectories(parentTemporaryDirectory.resolve("plugins/.git"));
			Path parentSentinel = Files.writeString(parentTemporaryDirectory.resolve("other-state"), "preserve");
			Path isolatedHome = root.resolve("isolated");

			boolean seeded = CodexCuratedPluginCache.seed(parentHome, isolatedHome, Map.of(), Yielding.executableFiles());

			requireThat(seeded, "seeded").isFalse();
			requireThat(Files.exists(isolatedHome.resolve(".tmp")), "isolatedTemporaryDirectory").isFalse();
			requireThat(Files.readString(parentSentinel), "parentState").isEqualTo("preserve");
		}
	}

	/**
	 * Verifies an owned partial-cache cleanup failure is reported with both filesystem failures.
	 *
	 * @throws IOException if fixture setup or cleanup fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void reportsPartialCacheCleanupFailure() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = createFixtureRoot(jvm, cleanup);
			Path parentHome = createVerifiedParent(root);
			Path isolatedHome = root.resolve("isolated");
			IOException seedFailure = new IOException("checkout failed");
			IOException cleanupFailure = new IOException("cleanup failed");
			List<GitRequest> requests = new ArrayList<>();
			Map<String, String> environment = Map.of("PATH", "", "CAT_CACHE_TEST", "controlled");
			ProcessLauncher launcher = successfulRequestThenFailure(requests, seedFailure);
			ExecutableFiles executables = controlledExecutables(launcher);
			Cleanup failedCleanup = directory ->
			{
				throw cleanupFailure;
			};
			CodexCuratedPluginCacheAccess access = CodexCuratedPluginCacheSecrets.
				getCodexCuratedPluginCacheAccess();

			try
			{
				access.seed(parentHome, isolatedHome, environment, executables, failedCleanup);
			}
			catch (IOException failure)
			{
				requireThat(requests, "gitRequests").isEqualTo(List.of(
					new GitRequest(List.of("git", "clone", "--quiet", "--shared", "--no-checkout",
						parentHome.resolve(".tmp/plugins").toString(), isolatedHome.resolve(".tmp/plugins").toString()),
						isolatedHome, environment),
					new GitRequest(List.of("git", "checkout", "--quiet", "--detach", REVISION),
						isolatedHome.resolve(".tmp/plugins"), environment)));
				requireThat(failure.getMessage(), "failureMessage").contains(isolatedHome.resolve(".tmp").toString());
				requireThat(failure.getCause(), "cleanupCause").isReferenceEqualTo(cleanupFailure, "cleanupFailure");
				requireThat(List.of(failure.getSuppressed()), "suppressedFailures").
					contains(seedFailure);
				throw failure;
			}
		}
	}

	/**
	 * Verifies a failed seed returns false after successfully removing its owned partial cache.
	 *
	 * @throws IOException if fixture setup or cleanup fails
	 */
	@Test
	public void removesPartialCacheAfterSeedFailure() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = createFixtureRoot(jvm, cleanup);
			Path parentHome = createVerifiedParent(root);
			Path isolatedHome = root.resolve("isolated");
			List<GitRequest> requests = new ArrayList<>();
			Map<String, String> environment = Map.of("PATH", "", "CAT_CACHE_TEST", "controlled");
			IOException seedFailure = new IOException("checkout failed");
			ProcessLauncher launcher = successfulRequestThenFailure(requests, seedFailure);
			ExecutableFiles executables = controlledExecutables(launcher);
			Cleanup removePartial = TemporaryDirectories::deleteRecursively;

			boolean seeded = CodexCuratedPluginCacheSecrets.getCodexCuratedPluginCacheAccess().
				seed(parentHome, isolatedHome, environment, executables, removePartial);

			requireThat(requests, "gitRequests").isEqualTo(List.of(
				new GitRequest(List.of("git", "clone", "--quiet", "--shared", "--no-checkout",
					parentHome.resolve(".tmp/plugins").toString(), isolatedHome.resolve(".tmp/plugins").toString()),
					isolatedHome, environment),
				new GitRequest(List.of("git", "checkout", "--quiet", "--detach", REVISION),
					isolatedHome.resolve(".tmp/plugins"), environment)));
			requireThat(seeded, "seeded").isFalse();
			requireThat(Files.exists(isolatedHome.resolve(".tmp")), "partialCache").isFalse();
		}
	}

	/**
	 * Verifies successful Git responses publish the verified revision and that CAT requests the exact clone and checkout.
	 *
	 * @throws IOException if fixture setup or cache seeding fails
	 */
	@Test
	public void seedsVerifiedSnapshot() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = createFixtureRoot(jvm, cleanup);
			Path parentHome = createVerifiedParent(root);
			Path isolatedHome = root.resolve("isolated");
			List<GitRequest> requests = new ArrayList<>();
			Map<String, String> environment = Map.of("PATH", "", "CAT_CACHE_TEST", "controlled");
			ProcessLauncher launcher = builder ->
			{
				requests.add(new GitRequest(List.copyOf(builder.command()), builder.directory().toPath(),
					Map.copyOf(builder.environment())));
				return new ExitedProcess(builder, 0, "", "");
			};
			ExecutableFiles executables = controlledExecutables(launcher);

			boolean seeded = CodexCuratedPluginCache.seed(parentHome, isolatedHome, environment, executables);

			requireThat(seeded, "seeded").isTrue();
			requireThat(requests, "gitRequests").isEqualTo(List.of(
				new GitRequest(List.of("git", "clone", "--quiet", "--shared", "--no-checkout",
					parentHome.resolve(".tmp/plugins").toString(), isolatedHome.resolve(".tmp/plugins").toString()),
					isolatedHome, environment),
				new GitRequest(List.of("git", "checkout", "--quiet", "--detach", REVISION),
					isolatedHome.resolve(".tmp/plugins"), environment)));
			requireThat(Files.readString(isolatedHome.resolve(".tmp/plugins.sha")), "childRevision").
				isEqualTo(REVISION + System.lineSeparator());
		}
	}

	/**
	 * Verifies a preexisting child temporary directory and sibling files are preserved when seeding is refused.
	 *
	 * @throws IOException if fixture setup or cache seeding fails
	 */
	@Test
	public void preservesUnownedChildTemporaryDirectory() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = createFixtureRoot(jvm, cleanup);
			Path parentHome = createVerifiedParent(root);
			Path isolatedHome = root.resolve("isolated");
			Path childTemporaryDirectory = Files.createDirectories(isolatedHome.resolve(".tmp"));
			Path sibling = Files.writeString(childTemporaryDirectory.resolve("other-state"), "preserve");
			ExecutableFiles executables = controlledExecutables(builder ->
				new ExitedProcess(builder, 0, "", ""));

			boolean seeded = CodexCuratedPluginCache.seed(parentHome, isolatedHome, Map.of(), executables);

			requireThat(seeded, "seeded").isFalse();
			requireThat(Files.readString(sibling), "siblingState").isEqualTo("preserve");
			requireThat(Files.exists(childTemporaryDirectory), "childTemporaryDirectory").isTrue();
			requireThat(Files.exists(parentHome.resolve(".tmp/plugins.sha")), "parentRevision").isTrue();
		}
	}

	/**
	 * Creates a temporary fixture root for one cache test.
	 *
	 * @param jvm the test's temporary JVM scope
	 * @param cleanup the cleanup scope that owns the root
	 * @return the fixture root
	 * @throws IOException if the fixture root cannot be created
	 */
	private static Path createFixtureRoot(TestJvmScope jvm, TemporaryDirectories cleanup) throws IOException
	{
		return cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-curated-plugin-cache-"));
	}

	/**
	 * Creates a parent cache with the recorded verified revision used by cache-seeding tests.
	 *
	 * @param root the fixture root
	 * @return the parent Codex home
	 * @throws IOException if the parent snapshot cannot be created
	 */
	private static Path createVerifiedParent(Path root) throws IOException
	{
		Path parentHome = root.resolve("parent");
		Files.createDirectories(parentHome.resolve(".tmp/plugins/.git"));
		Files.writeString(parentHome.resolve(".tmp/plugins.sha"), REVISION + "\n");
		return parentHome;
	}

	/**
	 * Records Git requests and supplies a successful command response followed by the supplied failure.
	 *
	 * @param requests    receives immutable snapshots of outgoing Git requests
	 * @param seedFailure the failure supplied for the second request
	 * @return the launcher that supplies the controlled command results
	 */
	private static ProcessLauncher successfulRequestThenFailure(List<GitRequest> requests, IOException seedFailure)
	{
		Deque<ProcessLauncher> responses = new ArrayDeque<>(List.of(
			builder -> new ExitedProcess(builder, 0, "", ""),
			builder ->
			{
				throw seedFailure;
			}));
		return builder ->
		{
			requests.add(new GitRequest(List.copyOf(builder.command()), builder.directory().toPath(),
				Map.copyOf(builder.environment())));
			return responses.removeFirst().start(builder);
		};
	}

	/**
	 * Creates executable-file access with the supplied controlled Git launcher.
	 *
	 * @param launcher controls Git starts without launching a process
	 * @return the executable-file access
	 */
	private static ExecutableFiles controlledExecutables(ProcessLauncher launcher)
	{
		return ExecutableFilesSecrets.getExecutableFilesAccess().create(() -> {}, Clock.systemUTC(), launcher);
	}

	/**
	 * An immutable snapshot of the Git operation CAT requested.
	 *
	 * @param command     executable and arguments sent to Git
	 * @param directory   Git's working directory
	 * @param environment environment supplied to Git
	 */
	private record GitRequest(List<String> command, Path directory, Map<String, String> environment)
	{
	}
}
