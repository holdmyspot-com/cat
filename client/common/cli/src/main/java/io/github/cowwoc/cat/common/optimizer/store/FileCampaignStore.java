package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignHeader;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import tools.jackson.databind.json.JsonMapper;

/**
 * Stores campaigns under {@code <base>/.cat/work/optimizer/<campaign-id>/}: the snapshot in {@code campaign.json},
 * one record per logical trial in {@code trials/<sha256 of the trial key>.json}, and the delivery journal in
 * {@code delivery.json}.
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
	private static final String JOURNAL = "delivery.json";
	private static final String RECORD_SUFFIX = ".json";
	private static final String MALFORMED = "is malformed";
	private final Path base;
	private final JsonMapper json;
	private final CampaignJson codec;
	private final DeliveryJournalJson journalCodec;
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
		this.base = base;
		this.json = json;
		this.codec = new CampaignJson(json);
		this.journalCodec = new DeliveryJournalJson(json);
		this.afterStep = afterStep;
	}

	/**
	 * Returns the directory that holds a campaign's files.
	 *
	 * @param base the base worktree
	 * @param campaignId the campaign's identifier
	 * @return the campaign directory below {@code .cat/work/optimizer}
	 * @throws NullPointerException if any argument is null
	 */
	public static Path directory(Path base, String campaignId)
	{
		requireThat(base, "base").isNotNull();
		requireThat(campaignId, "campaignId").isNotNull();
		return root(base).resolve(campaignId);
	}

	/**
	 * Returns the directory that holds all campaigns of a worktree.
	 *
	 * @param base the base worktree
	 * @return the campaigns directory
	 */
	private static Path root(Path base)
	{
		return base.resolve(".cat/work/optimizer");
	}

	@Override
	Optional<Campaign> readSnapshot(String campaignId) throws IOException
	{
		Path file = directory(base, campaignId).resolve(SNAPSHOT);
		if (Files.notExists(file))
			return Optional.empty();
		Campaign campaign = JsonFiles.read(json, file, codec::readCampaign, MALFORMED);
		requireOwner(file, campaignId, campaign.header());
		return Optional.of(campaign);
	}

	@Override
	void writeSnapshot(Campaign campaign) throws IOException
	{
		Path directory = directory(base, campaign.header().id());
		AtomicFiles.createDirectories(directory);
		JsonFiles.replace(json, directory.resolve(SNAPSHOT), codec.writeCampaign(campaign), afterStep);
	}

	@Override
	Optional<TrialRecord> readRecord(String campaignId, TrialKey key) throws IOException
	{
		Path file = recordFile(campaignId, key);
		if (Files.notExists(file))
			return Optional.empty();
		TrialRecord record = JsonFiles.read(json, file, codec::readRecord, MALFORMED);
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
		AtomicFiles.createDirectories(file.getParent());
		JsonFiles.replace(json, file, codec.writeRecord(record), afterStep);
	}

	@Override
	public List<CampaignHeader> listHeaders() throws IOException
	{
		Path root = root(base);
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
			CampaignHeader header = JsonFiles.read(json, file, codec::readSnapshotHeader, MALFORMED);
			requireOwner(file, directory.getFileName().toString(), header);
			headers.add(header);
		}
		return headers;
	}

	@Override
	Optional<DeliveryJournal> readJournal(String campaignId) throws IOException
	{
		Path file = directory(base, campaignId).resolve(JOURNAL);
		if (Files.notExists(file))
			return Optional.empty();
		return Optional.of(JsonFiles.read(json, file, journalCodec::read, MALFORMED));
	}

	@Override
	void replaceJournal(String campaignId, DeliveryJournal journal) throws IOException
	{
		Path directory = directory(base, campaignId);
		AtomicFiles.createDirectories(directory);
		JsonFiles.replace(json, directory.resolve(JOURNAL), journalCodec.write(journal), afterStep);
	}

	/**
	 * Returns the file that holds a trial's record.
	 *
	 * @param campaignId the campaign's identifier
	 * @param key the trial's key
	 * @return the file
	 */
	private Path recordFile(String campaignId, TrialKey key)
	{
		String keyText = key.purpose().name() + "\n" + key.series() + "\n" + key.snapshot().hex() + "\n" +
			key.category() + "\n" + key.sequence();
		return directory(base, campaignId).resolve(TRIALS).resolve(Sha256.of(keyText.getBytes(UTF_8)).hex() +
			RECORD_SUFFIX);
	}

	/**
	 * Checks that a snapshot belongs to the campaign whose directory holds it.
	 *
	 * @param file the snapshot
	 * @param campaignId the identifier that the directory names
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
}
