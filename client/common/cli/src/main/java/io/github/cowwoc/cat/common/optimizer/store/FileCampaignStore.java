package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignHeader;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores campaigns under {@code <base>/.cat/work/optimizer/<campaign-id>/}: the snapshot in {@code campaign.json} and
 * one record per logical trial in {@code trials/<sha256 of the trial key>.json}.
 * <p>
 * Every file is replaced atomically: the content is written to a temporary file next to the target, forced to the
 * storage device, renamed over the target, and the directory is forced. A crash leaves either the old or the new
 * content. Readers never read the temporary files, and the next write of the same target replaces a leftover one.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe. One process at a time may write a campaign.
 */
public final class FileCampaignStore extends AbstractCampaignStore
{
	private static final String SNAPSHOT = "campaign.json";
	private static final String TRIALS = "trials";
	private static final String RECORD_SUFFIX = ".json";
	private static final String TEMPORARY_SUFFIX = ".tmp";
	private final Path root;
	private final JsonMapper json;
	private final CampaignJson codec;
	private final Consumer<WriteStep> afterStep;

	/**
	 * Creates a store.
	 *
	 * @param base the base worktree
	 * @param json the JSON mapper
	 * @throws NullPointerException if any argument is null
	 */
	public FileCampaignStore(Path base, JsonMapper json)
	{
		this(base, json, _ ->
		{
		});
	}

	/**
	 * Creates a store that reports each completed write step.
	 *
	 * @param base the base worktree
	 * @param json the JSON mapper
	 * @param afterStep receives each step after it completes; an exception that it throws stops the write at that
	 *   point, as a crash would
	 * @throws NullPointerException if any argument is null
	 */
	public FileCampaignStore(Path base, JsonMapper json, Consumer<WriteStep> afterStep)
	{
		requireThat(base, "base").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(afterStep, "afterStep").isNotNull();
		this.root = base.resolve(".cat").resolve("work").resolve("optimizer");
		this.json = json;
		this.codec = new CampaignJson(json);
		this.afterStep = afterStep;
	}

	@Override
	Optional<Campaign> readSnapshot(String campaignId) throws IOException
	{
		Path file = root.resolve(campaignId).resolve(SNAPSHOT);
		if (Files.notExists(file))
			return Optional.empty();
		JsonNode tree = readTree(file);
		Campaign campaign;
		try
		{
			campaign = codec.readCampaign(tree);
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			throw malformed(file, e);
		}
		requireOwner(file, campaignId, campaign.header());
		return Optional.of(campaign);
	}

	@Override
	void writeSnapshot(Campaign campaign) throws IOException
	{
		Path directory = root.resolve(campaign.header().id());
		createDirectories(directory);
		replace(directory.resolve(SNAPSHOT), codec.writeCampaign(campaign));
	}

	@Override
	Optional<TrialRecord> readRecord(String campaignId, TrialKey key) throws IOException
	{
		Path file = recordFile(campaignId, key);
		if (Files.notExists(file))
			return Optional.empty();
		JsonNode tree = readTree(file);
		TrialRecord record;
		try
		{
			record = codec.readRecord(tree);
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			throw malformed(file, e);
		}
		if (!record.key().equals(key))
		{
			throw new IOException("The trial record " + file + " belongs to a different trial.\n" +
				"Expected: " + key + "\n" +
				"Actual  : " + record.key());
		}
		return Optional.of(record);
	}

	@Override
	void writeRecord(String campaignId, TrialRecord record) throws IOException
	{
		Path file = recordFile(campaignId, record.key());
		createDirectories(file.getParent());
		replace(file, codec.writeRecord(record));
	}

	@Override
	List<CampaignHeader> readHeaders() throws IOException
	{
		if (Files.notExists(root))
			return List.of();
		List<Path> directories;
		try (Stream<Path> entries = Files.list(root))
		{
			directories = entries.filter(Files::isDirectory).sorted().toList();
		}
		List<CampaignHeader> headers = new ArrayList<>();
		for (Path directory : directories)
		{
			Path file = directory.resolve(SNAPSHOT);
			if (Files.notExists(file))
				continue;
			JsonNode tree = readTree(file);
			CampaignHeader header;
			try
			{
				header = codec.readSnapshotHeader(tree);
			}
			catch (IllegalArgumentException | NullPointerException e)
			{
				throw malformed(file, e);
			}
			requireOwner(file, directory.getFileName().toString(), header);
			headers.add(header);
		}
		return headers;
	}

	/**
	 * Returns the file that holds a trial's record.
	 *
	 * @param campaignId the campaign's identity
	 * @param key the trial's identity
	 * @return the file
	 */
	private Path recordFile(String campaignId, TrialKey key)
	{
		String identity = key.purpose().name() + "\n" + key.series() + "\n" + key.snapshot().hex() + "\n" +
			key.category() + "\n" + key.sequence();
		return root.resolve(campaignId).resolve(TRIALS).resolve(Sha256.of(identity.getBytes(UTF_8)).hex() +
			RECORD_SUFFIX);
	}

	/**
	 * Parses a JSON file.
	 *
	 * @param file the file
	 * @return the document
	 * @throws IOException if the file cannot be read or is not valid JSON
	 */
	private JsonNode readTree(Path file) throws IOException
	{
		byte[] content = Files.readAllBytes(file);
		try
		{
			return json.readTree(content);
		}
		catch (JacksonException e)
		{
			throw new IOException("The file " + file + " is not valid JSON: " + e.getOriginalMessage(), e);
		}
	}

	/**
	 * Returns the exception for a document that does not describe a valid value.
	 *
	 * @param file the document's file
	 * @param cause the reason
	 * @return the exception
	 */
	private static IOException malformed(Path file, RuntimeException cause)
	{
		return new IOException("The file " + file + " is malformed: " + cause.getMessage(), cause);
	}

	/**
	 * Checks that a snapshot belongs to the campaign whose directory holds it.
	 *
	 * @param file the snapshot
	 * @param campaignId the identity that the directory names
	 * @param header the snapshot's header
	 * @throws IOException if the snapshot belongs to a different campaign
	 */
	private static void requireOwner(Path file, String campaignId, CampaignHeader header) throws IOException
	{
		if (!header.id().equals(campaignId))
		{
			throw new IOException("The snapshot " + file + " belongs to campaign " + header.id() + ", not " +
				campaignId);
		}
	}

	/**
	 * Creates a directory and its missing ancestors, forcing each new entry to the storage device.
	 *
	 * @param directory the directory
	 * @throws IOException if a directory cannot be created or forced
	 */
	private static void createDirectories(Path directory) throws IOException
	{
		if (Files.isDirectory(directory))
			return;
		Path parent = directory.getParent();
		createDirectories(parent);
		Files.createDirectory(directory);
		force(parent);
	}

	/**
	 * Forces a directory's entries to the storage device.
	 *
	 * @param directory the directory
	 * @throws IOException if the directory cannot be forced
	 */
	private static void force(Path directory) throws IOException
	{
		try (FileChannel channel = FileChannel.open(directory, READ))
		{
			channel.force(true);
		}
	}

	/**
	 * Atomically replaces a file with a document.
	 *
	 * @param target the file
	 * @param document the document
	 * @throws IOException if the file cannot be replaced
	 */
	private void replace(Path target, JsonNode document) throws IOException
	{
		byte[] content = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);
		Path temporary = target.resolveSibling(target.getFileName() + TEMPORARY_SUFFIX);
		try
		{
			try (FileChannel channel = FileChannel.open(temporary, CREATE, TRUNCATE_EXISTING, WRITE))
			{
				ByteBuffer buffer = ByteBuffer.wrap(content);
				while (buffer.hasRemaining())
					channel.write(buffer);
				afterStep.accept(WriteStep.WRITE_TEMPORARY);
				channel.force(true);
				afterStep.accept(WriteStep.SYNC_TEMPORARY);
			}
			Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			try
			{
				Files.deleteIfExists(temporary);
			}
			catch (IOException suppressed)
			{
				e.addSuppressed(suppressed);
			}
			throw e;
		}
		afterStep.accept(WriteStep.RENAME);
		force(target.getParent());
		afterStep.accept(WriteStep.SYNC_DIRECTORY);
	}
}
