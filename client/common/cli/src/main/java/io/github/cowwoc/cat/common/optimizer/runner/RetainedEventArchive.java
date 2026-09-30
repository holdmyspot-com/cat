package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Copies complete native JSONL records into immutable campaign evidence in their observed order.
 */
public final class RetainedEventArchive
{
	private static final int MAXIMUM_EVENT_BYTES = 64 * 1024 * 1024;

	/** Whether a reserved turn without emitted records may retain only its source hash. */
	public enum EmptyTurnPolicy
	{
		/** A successful native turn must emit records. */
		REJECT,
		/** Interrupted or failed dispatch may end before emitting any record. */
		RETAIN_SOURCE_HASH
	}

	/**
	 * The immutable native event references and hashes of their source turn files.
	 *
	 * @param events one evidence reference per ordered native record
	 * @param sourceSha256 one source-file hash per supplied turn file
	 */
	public record Archive(List<EvidenceRef> events, List<String> sourceSha256)
	{
		/**
		 * Copies the archive index without changing its order.
		 *
		 * @param events ordered native event references
		 * @param sourceSha256 ordered turn-file hashes
		 */
		public Archive
		{
			events = List.copyOf(events);
			sourceSha256 = List.copyOf(sourceSha256);
		}
	}

	/**
	 * Archives each complete line from ordered retained native turn files.
	 *
	 * @param eventFiles native event files in turn order
	 * @param store the campaign repository that owns immutable evidence
	 * @return ordered event references and source-file hashes
	 * @throws IOException if a file is incomplete, changes during reading, or cannot be retained
	 */
	public static Archive archive(List<Path> eventFiles, CampaignStore store) throws IOException
	{
		return archive(eventFiles, store, EmptyTurnPolicy.REJECT);
	}

	/**
	 * Archives ordered turn files with an explicit policy for a reserved turn that emitted no records.
	 *
	 * @param eventFiles native event files in turn order
	 * @param store immutable record repository
	 * @param emptyTurnPolicy whether an empty source file may retain only its hash
	 * @return exact ordered records and one hash per source file
	 * @throws IOException if a source violates the selected policy or cannot be retained
	 */
	public static Archive archive(List<Path> eventFiles, CampaignStore store, EmptyTurnPolicy emptyTurnPolicy)
		throws IOException
	{
		eventFiles = List.copyOf(eventFiles);
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(emptyTurnPolicy, "emptyTurnPolicy");
		if (eventFiles.isEmpty())
			throw new IllegalArgumentException("At least one native turn event file is required");

		List<EvidenceRef> events = new ArrayList<>();
		List<String> sourceSha256 = new ArrayList<>(eventFiles.size());
		for (Path file : eventFiles)
			sourceSha256.add(archiveFile(file, store, events, emptyTurnPolicy));
		return new Archive(events, sourceSha256);
	}

	/**
	 * Streams one stable turn file into immutable record evidence.
	 *
	 * @param file the retained native turn file
	 * @param store the campaign repository
	 * @param events ordered references accumulated across turn files
	 * @param emptyTurnPolicy whether an empty source file may retain only its hash
	 * @return the SHA-256 hash of the exact source-file bytes
	 * @throws IOException if the file is not a complete regular JSONL file or evidence cannot be retained
	 */
	private static String archiveFile(Path file, CampaignStore store, List<EvidenceRef> events,
		EmptyTurnPolicy emptyTurnPolicy) throws IOException
	{
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Native turn events are not a regular file: " + file);
		MessageDigest digest = sha256();
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
			InputStream input = new BufferedInputStream(Channels.newInputStream(channel)))
		{
			long size = channel.size();
			if (size == 0 && emptyTurnPolicy == EmptyTurnPolicy.REJECT)
				throw new IOException("Native turn events are empty: " + file);
			ByteArrayOutputStream record = new ByteArrayOutputStream();
			while (true)
			{
				int value = input.read();
				if (value == -1)
					break;
				digest.update((byte) value);
				record.write(value);
				if (record.size() > MAXIMUM_EVENT_BYTES)
					throw new IOException("Native event exceeds the supported record size: " + file);
				if (value != '\n')
					continue;
				if (record.size() == 1)
					throw new IOException("Native turn events contain an empty record: " + file);
				events.add(store.appendImmutableBytes("native-events", record.toByteArray()));
				record.reset();
			}
			if (record.size() != 0)
				throw new IOException("Native turn events have an incomplete final record: " + file);
			if (channel.size() != size)
				throw new IOException("Native turn events changed while being archived: " + file);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Obtains the JDK's SHA-256 digest for one streamed event file.
	 *
	 * @return the message digest
	 * @throws IOException if the runtime lacks SHA-256
	 */
	private static MessageDigest sha256() throws IOException
	{
		try
		{
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException failure)
		{
			throw new IOException("SHA-256 is unavailable", failure);
		}
	}

	/**
	 * Prevents construction.
	 */
	private RetainedEventArchive()
	{
	}
}
