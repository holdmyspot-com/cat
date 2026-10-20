package com.holdmyspot.cat.codex.runner.test;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexCuratedPluginCache;
import com.holdmyspot.cat.common.TemporaryDirectories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

/**
 * Tests {@link CodexCuratedPluginCache}.
 */
public final class CodexCuratedPluginCacheTest
{
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(
				Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize()),
				"cat-curated-plugin-cache-"));
			Path parentHome = root.resolve("parent");
			Files.createDirectories(parentHome.resolve(".tmp/plugins/.git"));
			Path isolatedHome = root.resolve("isolated");

			boolean seeded = CodexCuratedPluginCache.seed(parentHome, isolatedHome, Map.of(), Yielding.executableFiles());

			requireThat(seeded, "seeded").isFalse();
			requireThat(Files.exists(isolatedHome.resolve(".tmp")), "isolatedTemporaryDirectory").isFalse();
		}
	}
}
