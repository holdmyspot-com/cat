package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.HarnessCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

/**
 * Tests {@link CodexCuratedPluginCache}.
 */
public final class CodexCuratedPluginCacheTest
{
	/**
	 * Verifies a child starts from the parent's verified curated-plugin revision without sharing its working files.
	 *
	 * @throws IOException if fixture setup or cache seeding fails
	 */
	@Test
	public void seedsVerifiedSnapshotIntoIsolatedHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-curated-plugin-cache-"));
			Path parentHome = root.resolve("parent");
			Path repository = Files.createDirectories(parentHome.resolve(".tmp/plugins"));
			Map<String, String> environment = new HashMap<>(System.getenv());
			initializeRepository(repository, environment);
			String revision = Files.readString(repository.resolve(".git/refs/heads/main")).strip();
			Files.writeString(parentHome.resolve(".tmp/plugins.sha"), revision + System.lineSeparator());
			Path isolatedHome = root.resolve("isolated");

			boolean seeded = CodexCuratedPluginCache.seed(parentHome, isolatedHome, environment);

			Path childRepository = isolatedHome.resolve(".tmp/plugins");
			requireThat(seeded, "seeded").isTrue();
			requireThat(Files.isSymbolicLink(childRepository), "childRepositoryIsSymbolicLink").isFalse();
			requireThat(Files.readString(isolatedHome.resolve(".tmp/plugins.sha")), "childRevision").
				isEqualTo(revision + System.lineSeparator());
			requireThat(Files.readString(childRepository.resolve("catalog.txt")), "childCatalog").isEqualTo("catalog");
			Files.writeString(childRepository.resolve("catalog.txt"), "child-only");
			requireThat(Files.readString(repository.resolve("catalog.txt")), "parentCatalog").isEqualTo("catalog");
		}
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
			Path root = cleanup.add(Files.createTempDirectory("cat-curated-plugin-cache-"));
			Path parentHome = root.resolve("parent");
			Files.createDirectories(parentHome.resolve(".tmp/plugins/.git"));
			Path isolatedHome = root.resolve("isolated");

			boolean seeded = CodexCuratedPluginCache.seed(parentHome, isolatedHome, System.getenv());

			requireThat(seeded, "seeded").isFalse();
			requireThat(Files.exists(isolatedHome.resolve(".tmp")), "isolatedTemporaryDirectory").isFalse();
		}
	}

	/**
	 * Creates a local curated-plugin repository fixture at one known revision.
	 *
	 * @param repository the empty repository directory
	 * @param environment the Git execution environment
	 * @throws IOException if Git fixture initialization fails
	 */
	private static void initializeRepository(Path repository, Map<String, String> environment) throws IOException
	{
		HarnessCommand.execute(List.of("git", "init", "--quiet", "--initial-branch=main"), repository, environment);
		Files.writeString(repository.resolve("catalog.txt"), "catalog");
		HarnessCommand.execute(List.of("git", "add", "catalog.txt"), repository, environment);
		HarnessCommand.execute(List.of("git", "-c", "user.name=CAT fixture", "-c",
			"user.email=cat-fixture@example.invalid", "-c", "commit.gpgSign=false", "commit", "--quiet", "-m",
			"Curated plugin snapshot"), repository, environment);
	}
}
