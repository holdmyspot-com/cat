package com.holdmyspot.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.CandidateOverlays;
import com.holdmyspot.cat.common.optimizer.port.ForkVersions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

import tools.jackson.databind.json.JsonMapper;

/**
 * Stores the overlays of a campaign's candidates in {@code <campaign>/overlays/<snapshot>.json}, one file per
 * candidate, named by the candidate's snapshot.
 * <p>
 * A file holds the fork's identity and the overlay's entries with their bytes and modes, but not the fork's files:
 * the fork is recorded once in campaign storage, and the caller supplies its inventory. Each file is written once and
 * replaced atomically (see {@link WriteStep}), so a crash leaves either no overlay or the complete overlay.
 * <p>
 * Loading rebuilds the overlay on the fork that the file names, which is the campaign's current fork or an earlier
 * one that {@link ForkVersions} supplies, and returns it only if its identity equals the requested snapshot.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe. One process at a time may write a campaign.
 */
public final class FileCandidateOverlays implements CandidateOverlays
{
	private static final String SUFFIX = ".json";
	private final Path directory;
	private final FileInventory fork;
	private final ForkVersions versions;
	private final JsonMapper json;
	private final FileReplacer replacer;
	private final OverlayJson codec;
	private final Consumer<WriteStep> afterStep;

	/**
	 * Creates the overlays of a campaign that has run on one fork only.
	 *
	 * @param campaign the campaign storage directory
	 * @param fork the files recorded at the campaign's fork
	 * @param json the JSON mapper
	 * @param replacer replaces the overlay files
	 * @throws NullPointerException if any argument is null
	 */
	public FileCandidateOverlays(Path campaign, FileInventory fork, JsonMapper json, FileReplacer replacer)
	{
		this(campaign, fork, ForkVersions.NONE, json, replacer);
	}

	/**
	 * Creates the overlays of a campaign.
	 *
	 * @param campaign the campaign storage directory
	 * @param fork the files recorded at the campaign's current fork
	 * @param versions the earlier forks that the campaign ran on
	 * @param json the JSON mapper
	 * @param replacer replaces the overlay files
	 * @throws NullPointerException if any argument is null
	 */
	public FileCandidateOverlays(Path campaign, FileInventory fork, ForkVersions versions, JsonMapper json,
		FileReplacer replacer)
	{
		this(campaign, fork, versions, json, replacer, _ ->
		{
		});
	}

	/**
	 * Creates the overlays of a campaign that report each completed write step.
	 *
	 * @param campaign the campaign storage directory
	 * @param fork the files recorded at the campaign's fork
	 * @param json the JSON mapper
	 * @param replacer replaces the overlay files
	 * @param afterStep receives each step after it completes; an exception that it throws stops the write at that
	 *   point, as a crash would
	 * @throws NullPointerException if any argument is null
	 */
	public FileCandidateOverlays(Path campaign, FileInventory fork, JsonMapper json, FileReplacer replacer,
		Consumer<WriteStep> afterStep)
	{
		this(campaign, fork, ForkVersions.NONE, json, replacer, afterStep);
	}

	/**
	 * Creates the overlays of a campaign that report each completed write step.
	 *
	 * @param campaign the campaign storage directory
	 * @param fork the files recorded at the campaign's current fork
	 * @param versions the earlier forks that the campaign ran on
	 * @param json the JSON mapper
	 * @param replacer replaces the overlay files
	 * @param afterStep receives each step after it completes; an exception that it throws stops the write at that
	 *   point, as a crash would
	 * @throws NullPointerException if any argument is null
	 */
	private FileCandidateOverlays(Path campaign, FileInventory fork, ForkVersions versions, JsonMapper json,
		FileReplacer replacer, Consumer<WriteStep> afterStep)
	{
		requireThat(campaign, "campaign").isNotNull();
		requireThat(fork, "fork").isNotNull();
		requireThat(versions, "versions").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(afterStep, "afterStep").isNotNull();
		this.directory = campaign.resolve("overlays");
		this.fork = fork;
		this.versions = versions;
		this.json = json;
		this.replacer = replacer;
		this.codec = new OverlayJson(json);
		this.afterStep = afterStep;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws IOException if the overlay file cannot be read, is malformed, is on a different fork, or describes an
	 *   overlay whose identity differs from {@code snapshot}; the message names the file
	 */
	@Override
	public Optional<Overlay> find(Sha256 snapshot) throws IOException
	{
		requireThat(snapshot, "snapshot").isNotNull();
		Path file = file(snapshot);
		if (Files.notExists(file))
			return Optional.empty();
		// An overlay holds only the prompt files a candidate changes, so the whole document fits in memory
		FileInventory recorded = forkOf(JsonFiles.read(json, file, codec::fork, "does not describe an overlay"));
		Overlay overlay = JsonFiles.read(json, file, tree -> codec.read(tree, recorded),
			"does not describe an overlay on this campaign's fork");
		Sha256 identity = overlay.identity();
		if (!identity.equals(snapshot))
		{
			throw new IOException("The overlay file " + file + " describes a different candidate.\n" +
				"Expected snapshot: " + snapshot.hex() + "\n" +
				"Actual snapshot  : " + identity.hex());
		}
		return Optional.of(overlay);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws IllegalArgumentException if {@code overlay} is on a different fork than these overlays
	 */
	@Override
	public void save(Overlay overlay) throws IOException
	{
		requireThat(overlay, "overlay").isNotNull();
		requireThat(overlay.fork(), "overlay.fork()").isEqualTo(fork, "fork");
		Path file = file(overlay.identity());
		if (Files.exists(file))
			return;
		AtomicFiles.createDirectories(directory);
		JsonFiles.replace(json, replacer, file, codec.write(overlay), afterStep);
	}

	/**
	 * Returns the files of the fork that an overlay file names.
	 *
	 * @param identity the fork's identity
	 * @return the campaign's current fork, or the earlier fork with that identity; the current fork if the campaign
	 *   never ran on such a fork, so that reading the overlay reports the mismatch
	 * @throws IOException if the earlier forks cannot be read
	 */
	private FileInventory forkOf(Sha256 identity) throws IOException
	{
		if (identity.equals(fork.identity()))
			return fork;
		return versions.find(identity).orElse(fork);
	}

	/**
	 * Returns the file that holds a candidate's overlay.
	 *
	 * @param snapshot the candidate's snapshot
	 * @return the file
	 */
	private Path file(Sha256 snapshot)
	{
		return directory.resolve(snapshot.hex() + SUFFIX);
	}
}
