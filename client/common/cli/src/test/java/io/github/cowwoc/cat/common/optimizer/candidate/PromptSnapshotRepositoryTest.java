package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.testng.annotations.Test;

/** Tests durable prompt snapshot persistence. */
public final class PromptSnapshotRepositoryTest
{
	/** Creates the test fixture. */
	public PromptSnapshotRepositoryTest()
	{
	}

	/**
	 * Capturing files publishes manifest entries in project-relative path order.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void ordersCapturedManifestPaths() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("snapshot-entry-order-"));
			List<Path> paths = List.of(Path.of("a.md"), Path.of("b.md"), Path.of("c.md"), Path.of("d.md"),
				Path.of("e.md"), Path.of("f.md"), Path.of("g.md"), Path.of("h.md"));
			for (Path path : paths)
				Files.writeString(root.resolve(path), path.toString());

			PromptSnapshot snapshot = PromptSnapshot.capture(root, paths);
			requireThat(snapshot.manifest().entries().stream().map(SnapshotEntry::relativePath).toList(),
				"manifestPaths").isEqualTo(paths);
		}
	}

	/**
	 * Restoring or republishing the same snapshot preserves a valid retained manifest's entry order and immutable bytes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void preservesRetainedManifestOrder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("snapshot-retained-order-"));
			Path project = Files.createDirectory(root.resolve("project"));
			Files.writeString(project.resolve("a.md"), "first");
			Files.writeString(project.resolve("b.md"), "second");
			PromptSnapshot captured = PromptSnapshot.capture(project, List.of(Path.of("a.md"), Path.of("b.md")));
			Path campaign = root.resolve("campaign");
			PromptSnapshotRepository repository = new PromptSnapshotRepository(campaign);
			repository.save(captured);
			List<SnapshotEntry> retainedEntries = new ArrayList<>(captured.manifest().entries());
			Collections.reverse(retainedEntries);
			SnapshotManifest retained = new SnapshotManifest(SnapshotManifest.SCHEMA_VERSION, captured.id(),
				retainedEntries);
			byte[] retainedBytes = SnapshotJson.mapper().writeValueAsBytes(retained);
			Path manifestFile = campaign.resolve("snapshots").resolve(captured.id() + ".json");
			// A prior producer may publish a valid different entry order under the same independently derived identity.
			Files.write(manifestFile, retainedBytes);

			PromptSnapshot restored = new PromptSnapshotRepository(campaign).load(captured.id());
			repository.save(restored);
			repository.save(captured);
			requireThat(Files.readAllBytes(manifestFile), "retainedManifestBytes").isEqualTo(retainedBytes);
			requireThat(restored.manifest().entries(), "retainedManifestEntries").isEqualTo(retainedEntries);
		}
	}

	/** Manifest entry paths must not gain a different identity through normalization. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*normalized project-relative.*")
	public void rejectsDotComponentsInManifestPath()
	{
		new SnapshotEntry(Path.of("prompts/../secret.md"), PromptSnapshot.FileKind.REGULAR, "0".repeat(64));
	}

	/**
	 * Saving then restarting preserves creation, deletion, rename, and non-UTF-8 bytes exactly.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void restartsWithExactBinaryPromptBytes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("snapshot-project-"));
			Path campaign = cleanup.add(Files.createTempDirectory("snapshot-campaign-"));
			Files.createDirectories(project.resolve("prompts/nested"));
			byte[] binary = {(byte) 0xff, 0, (byte) 0x80, 10};
			Files.write(project.resolve("prompts/nested/new.bin"), binary);
			PromptSnapshot captured = PromptSnapshot.capture(project, List.of(Path.of("prompts")));

			PromptSnapshotRepository writer = new PromptSnapshotRepository(campaign);
			String candidateId = writer.save(captured);
			PromptSnapshot restored = new PromptSnapshotRepository(campaign).load(candidateId);

			requireThat(restored.id(), "restoredId").isEqualTo(captured.id());
			requireThat(restored.entries().keySet(), "restoredPaths").containsExactly(
				List.of(Path.of("prompts/nested/new.bin")));
			requireThat(restored.blob(captured.entries().get(Path.of("prompts/nested/new.bin")).hash()).orElseThrow(),
				"restoredBinaryBytes").isEqualTo(binary);
		}
	}

	/**
	 * A candidate selected from the retained source snapshot keeps original prompt bytes and excludes unrelated files.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void selectsPromptPathsFromRetainedBytes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("snapshot-selection-project-"));
			Path campaign = cleanup.add(Files.createTempDirectory("snapshot-selection-campaign-"));
			Files.createDirectories(project.resolve("prompts/nested"));
			Files.writeString(project.resolve("prompts/target.md"), "original target");
			Files.writeString(project.resolve("prompts/nested/rule.md"), "original nested rule");
			Files.writeString(project.resolve("read-only.md"), "original read-only rule");
			Files.writeString(project.resolve("unrelated.txt"), "unrelated");
			PromptSnapshot full = PromptSnapshot.capture(project, List.of(Path.of("prompts"),
				Path.of("read-only.md"), Path.of("unrelated.txt")));
			Files.writeString(project.resolve("prompts/target.md"), "later worktree edit");
			PromptSnapshot selected = full.select(List.of(Path.of("prompts"), Path.of("read-only.md")));
			PromptSnapshotRepository repository = new PromptSnapshotRepository(campaign);
			PromptSnapshot restored = repository.load(repository.save(selected));
			requireThat(restored.entries().keySet(), "selectedPaths").isEqualTo(java.util.Set.of(
				Path.of("prompts/target.md"), Path.of("prompts/nested/rule.md"), Path.of("read-only.md")));
			requireThat(new String(restored.entries().get(Path.of("prompts/target.md")).bytes(),
				java.nio.charset.StandardCharsets.UTF_8), "retainedTarget").isEqualTo("original target");
			requireThat(restored.entries().containsKey(Path.of("unrelated.txt")), "unrelatedExcluded").isFalse();
		}
	}

	/**
	 * Loading refuses a missing blob, corrupt blob, and manifest whose claimed identity was not independently derived.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsInvalidPersistedArtifacts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("snapshot-invalid-project-"));
			Path campaign = cleanup.add(Files.createTempDirectory("snapshot-invalid-campaign-"));
			Files.createDirectories(project.resolve("prompts"));
			Files.write(project.resolve("prompts/rule.bin"), new byte[]{1, 2, 3});
			PromptSnapshot captured = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			PromptSnapshotRepository repository = new PromptSnapshotRepository(campaign);
			repository.save(captured);
			String hash = captured.entries().get(Path.of("prompts/rule.bin")).hash();
			Path blob = campaign.resolve("snapshot-content").resolve(hash + ".blob");

			Files.delete(blob);
			requireLoadFailure(repository, captured.id());
			Files.write(blob, new byte[]{4, 5, 6});
			requireLoadFailure(repository, captured.id());
			Files.write(blob, new byte[]{1, 2, 3});

			String falseId = "0".repeat(64);
			SnapshotManifest forged = new SnapshotManifest(SnapshotManifest.SCHEMA_VERSION, falseId,
				captured.manifest().entries());
			Files.write(campaign.resolve("snapshots").resolve(falseId + ".json"),
				SnapshotJson.mapper().writeValueAsBytes(forged));
			requireLoadFailure(repository, falseId);
		}
	}

	/**
	 * Loading rejects an unsupported manifest version before trying to read its content blob.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsUnsupportedManifestSchema() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("snapshot-schema-project-"));
			Path campaign = cleanup.add(Files.createTempDirectory("snapshot-schema-campaign-"));
			Files.createDirectories(project.resolve("prompts"));
			Files.writeString(project.resolve("prompts/rule.md"), "prompt");
			PromptSnapshot captured = PromptSnapshot.capture(project, List.of(Path.of("prompts/rule.md")));
			PromptSnapshotRepository repository = new PromptSnapshotRepository(campaign);
			repository.save(captured);
			Path manifest = campaign.resolve("snapshots").resolve(captured.id() + ".json");
			String original = Files.readString(manifest);
			String unsupported = original.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
			requireThat(unsupported.equals(original), "schemaChanged").isFalse();
			Files.writeString(manifest, unsupported);
			Files.delete(campaign.resolve("snapshot-content").resolve(
				captured.entries().get(Path.of("prompts/rule.md")).hash() + ".blob"));

			try
			{
				repository.load(captured.id());
				throw new AssertionError("Expected unsupported schema rejection");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "failureMessage").contains("manifest is malformed");
				requireThat(expected.getCause().getMessage(), "schemaFailure").contains(
					"Unsupported snapshot manifest schema");
			}
		}
	}

	/**
	 * A pre-existing address with different bytes refuses overwrite, so an orphan blob cannot publish a candidate.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesContentAddressCollision() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("snapshot-collision-project-"));
			Path campaign = cleanup.add(Files.createTempDirectory("snapshot-collision-campaign-"));
			Files.createDirectories(project.resolve("prompts"));
			Files.write(project.resolve("prompts/rule.bin"), new byte[]{1});
			PromptSnapshot captured = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			PromptSnapshotRepository repository = new PromptSnapshotRepository(campaign);
			repository.save(captured);
			String hash = captured.entries().get(Path.of("prompts/rule.bin")).hash();
			Files.write(campaign.resolve("snapshot-content").resolve(hash + ".blob"), new byte[]{2});

			try
			{
				repository.save(captured);
				throw new AssertionError("Expected immutable collision");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "collisionMessage").contains("collision");
			}
			Files.write(campaign.resolve("snapshot-content").resolve(hash + ".blob"), new byte[]{1});
			Files.write(campaign.resolve("snapshots").resolve(captured.id() + ".json"), new byte[]{0});
			try
			{
				repository.save(captured);
				throw new AssertionError("Expected manifest collision");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "manifestCollisionMessage").contains("collision");
			}
		}
	}

	/**
	 * Requires one persisted artifact load to fail.
	 *
	 * @param repository repository under test
	 * @param candidateId requested candidate
	 * @throws IOException if fixture I/O fails
	 */
	private static void requireLoadFailure(PromptSnapshotRepository repository, String candidateId) throws IOException
	{
		try
		{
			repository.load(candidateId);
			throw new AssertionError("Expected snapshot load failure");
		}
		catch (IOException expected)
		{
			requireThat(expected.getMessage(), "failureMessage").isNotBlank();
		}
	}
}
