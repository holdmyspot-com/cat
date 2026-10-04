package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Header;
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
 * Stores campaigns under {@code <base>/.cat/work/optimizer/<campaign-id>/}. The campaign's {@code held-out}
 * directory holds every file with held-out content: the snapshot in {@code campaign.json}, the proposal in
 * {@code proposal.json}, and one record per logical trial in {@code trials/<sha256 of the trial key>.json}. The
 * delivery journal is {@code delivery.json} in the campaign directory. The {@code held-out} and {@code trials}
 * directories exist from the first snapshot write.
 * <p>
 * A snapshot stored directly in the campaign directory belongs to an older format and is refused with a message that
 * asks the user to start a new campaign.
 * <p>
 * Every file is replaced atomically: the content is written to a temporary file next to the target, forced to the
 * storage device, renamed over the target, and the directory is forced. A crash leaves either the old or the new
 * content. Readers never read the temporary files, and the next write of the same target replaces a leftover one.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe. One process at a time may write a campaign.
 */
public final class FileCampaignStore extends AbstractCampaignStore
{
	/**
	 * The name of the file in a campaign's {@code held-out} directory that holds the proposal.
	 */
	public static final String PROPOSAL = "proposal.json";
	private static final String HELD_OUT = "held-out";
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
	 * Returns the directory that holds a campaign's held-out content: its snapshot, proposal and trial records.
	 *
	 * @param base the base worktree
	 * @param campaignId the campaign's identifier
	 * @return the campaign's {@code held-out} directory
	 * @throws NullPointerException if any argument is null
	 */
	public static Path heldOutDirectory(Path base, String campaignId)
	{
		return directory(base, campaignId).resolve(HELD_OUT);
	}

	/**
	 * Returns the {@code held-out} directories of a worktree's campaigns. Nested sessions of the development part must
	 * not read them.
	 *
	 * @param base the base worktree
	 * @return the real paths of those that exist, in no particular order; empty if the worktree has no campaigns
	 * @throws NullPointerException if {@code base} is null
	 * @throws IOException if the campaigns directory cannot be listed
	 */
	public static List<Path> heldOutDirectories(Path base) throws IOException
	{
		requireThat(base, "base").isNotNull();
		List<Path> paths = new ArrayList<>();
		for (Path directory : campaignDirectories(base))
		{
			Path heldOut = directory.resolve(HELD_OUT);
			if (Files.isDirectory(heldOut))
				paths.add(heldOut.toRealPath());
		}
		return paths;
	}

	/**
	 * Returns the campaign directories of a worktree.
	 *
	 * @param base the base worktree
	 * @return the directories in name order; empty if the worktree has no campaigns
	 * @throws IOException if the campaigns directory cannot be listed
	 */
	private static List<Path> campaignDirectories(Path base) throws IOException
	{
		Path root = root(base);
		if (Files.notExists(root))
			return List.of();
		try (Stream<Path> entries = Files.list(root))
		{
			return entries.filter(Files::isDirectory).sorted().toList();
		}
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
		Path directory = directory(base, campaignId);
		Path file = directory.resolve(HELD_OUT).resolve(SNAPSHOT);
		if (Files.notExists(file))
		{
			refuseOutsideHeldOut(directory);
			return Optional.empty();
		}
		Campaign campaign = JsonFiles.read(json, file, codec::readCampaign, MALFORMED);
		requireOwner(file, campaignId, campaign.header());
		return Optional.of(campaign);
	}

	@Override
	void writeSnapshot(Campaign campaign) throws IOException
	{
		Path heldOut = heldOutDirectory(base, campaign.header().id());
		// Sessions that start before the first trial record is written must already be denied the records' directory.
		AtomicFiles.createDirectories(heldOut.resolve(TRIALS));
		JsonFiles.replace(json, heldOut.resolve(SNAPSHOT), codec.writeCampaign(campaign), afterStep);
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
	public List<Header> listHeaders() throws IOException
	{
		List<Header> headers = new ArrayList<>();
		for (Path directory : campaignDirectories(base))
		{
			Path file = directory.resolve(HELD_OUT).resolve(SNAPSHOT);
			if (Files.notExists(file))
			{
				refuseOutsideHeldOut(directory);
				continue;
			}
			Header header = JsonFiles.read(json, file, codec::readSnapshotHeader, MALFORMED);
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
			key.cell() + "\n" + key.sequence();
		return heldOutDirectory(base, campaignId).resolve(TRIALS).resolve(Sha256.of(keyText.getBytes(UTF_8)).hex() +
			RECORD_SUFFIX);
	}

	/**
	 * Refuses a campaign whose snapshot is stored directly in its campaign directory, as older formats stored it.
	 *
	 * @param directory the campaign directory
	 * @throws IOException if the directory holds a snapshot
	 */
	private void refuseOutsideHeldOut(Path directory) throws IOException
	{
		Path file = directory.resolve(SNAPSHOT);
		if (Files.notExists(file))
			return;
		// Reading the header refuses the snapshot's format with the message that names its version
		JsonFiles.read(json, file, codec::readSnapshotHeader, MALFORMED);
		throw new IOException("The snapshot " + file + " is stored outside the campaign's " + HELD_OUT + " directory, " +
			"as older versions of CAT stored it. Start a new campaign.");
	}

	/**
	 * Checks that a snapshot belongs to the campaign whose directory holds it.
	 *
	 * @param file the snapshot
	 * @param campaignId the identifier that the directory names
	 * @param header the snapshot's header
	 * @throws IOException if the snapshot belongs to a different campaign
	 */
	private static void requireOwner(Path file, String campaignId, Header header) throws IOException
	{
		if (!header.id().equals(campaignId))
		{
			throw new IOException("The snapshot " + file + " belongs to campaign " + header.id() + ", not " +
				campaignId);
		}
	}
}
