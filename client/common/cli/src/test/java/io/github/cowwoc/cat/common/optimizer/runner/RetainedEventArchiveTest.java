package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import org.testng.annotations.Test;
import org.testng.Assert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Tests exact native JSONL record retention in a campaign repository.
 */
public final class RetainedEventArchiveTest
{
	/**
	 * Creates a test instance.
	 */
	public RetainedEventArchiveTest()
	{
	}

	/**
	 * An explicitly allowed empty turn retains its exact source hash without inventing a native event.
	 *
	 * @throws IOException if fixture files cannot be retained
	 */
	@Test
	public void retainsEmptyTurnOnlyWhenAllowed() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-empty-native-turn-"));
			Path file = Files.writeString(root.resolve("turn-1.events"), "");
			CampaignStore store = new CampaignStore(root.resolve("campaign"));
			Assert.expectThrows(IOException.class, () -> RetainedEventArchive.archive(List.of(file), store));
			RetainedEventArchive.Archive archive = RetainedEventArchive.archive(List.of(file), store,
				RetainedEventArchive.EmptyTurnPolicy.RETAIN_SOURCE_HASH);
			Assert.assertTrue(archive.events().isEmpty());
			Assert.assertEquals(archive.sourceSha256(),
				List.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
			Files.writeString(file, "{\"unfinished\":true}");
			Assert.expectThrows(IOException.class, () -> RetainedEventArchive.archive(List.of(file), store,
				RetainedEventArchive.EmptyTurnPolicy.RETAIN_SOURCE_HASH));
		}
	}

	/**
	 * Verifies native records remain ordered and byte-exact across turn files.
	 *
	 * @throws IOException if the test-owned evidence cannot be written or read
	 */
	@Test
	public void archivesExactOrderedRecords() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-native-event-archive-"));
			Path first = Files.writeString(root.resolve("turn-1.events"), "{\"name\":\"café\"}\n{\"name\":\"first\"}\n");
			Path second = Files.writeString(root.resolve("turn-2.events"), "{\"name\":\"second\"}\n");
			CampaignStore store = new CampaignStore(root.resolve("campaign"));

			RetainedEventArchive.Archive archive = RetainedEventArchive.archive(List.of(first, second), store);
			requireThat(archive.events().size(), "eventCount").isEqualTo(3);
			requireThat(archive.sourceSha256().size(), "sourceCount").isEqualTo(2);
			String firstRecord = new String(store.readImmutableBytes(archive.events().get(0)), UTF_8);
			String secondRecord = new String(store.readImmutableBytes(archive.events().get(1)), UTF_8);
			String thirdRecord = new String(store.readImmutableBytes(archive.events().get(2)), UTF_8);
			requireThat(firstRecord, "firstRecord").isEqualTo("{\"name\":\"café\"}\n");
			requireThat(secondRecord, "secondRecord").isEqualTo("{\"name\":\"first\"}\n");
			requireThat(thirdRecord, "thirdRecord").isEqualTo("{\"name\":\"second\"}\n");
		}
	}

	/**
	 * Verifies an incomplete final record is never published as a complete native event.
	 *
	 * @throws IOException when a test-owned event file has no final newline
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*incomplete final record.*")
	public void rejectsIncompleteFinalRecord() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-native-event-archive-"));
			Path events = Files.writeString(root.resolve("turn-1.events"), "{\"name\":\"unfinished\"}");

			RetainedEventArchive.archive(List.of(events), new CampaignStore(root.resolve("campaign")));
		}
	}

	/**
	 * Verifies a replaced event-file path cannot redirect the archive reader.
	 *
	 * @throws IOException when the event path is a symbolic link
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*not a regular file.*")
	public void rejectsEventFileLink() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-native-event-archive-"));
			Path target = Files.writeString(root.resolve("target.events"), "{\"name\":\"target\"}\n");
			Path link = Files.createSymbolicLink(root.resolve("turn-1.events"), target.getFileName());

			RetainedEventArchive.archive(List.of(link), new CampaignStore(root.resolve("campaign")));
		}
	}
}
