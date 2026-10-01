package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.CandidateOverlays;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores the overlays of a campaign's candidates in {@code <campaign>/overlays/<snapshot>.json}, one file per
 * candidate, named by the candidate's snapshot.
 * <p>
 * A file holds the fork's identity and the overlay's entries with their bytes and modes, but not the fork's files:
 * the fork is recorded once in campaign storage, and the caller supplies its inventory. Each file is written once and
 * replaced atomically (see {@link WriteStep}), so a crash leaves either no overlay or the complete overlay.
 * <p>
 * Loading rebuilds the overlay on the fork and returns it only if its identity equals the requested snapshot.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe. One process at a time may write a campaign.
 */
public final class FileCandidateOverlays implements CandidateOverlays
{
	private static final String SUFFIX = ".json";
	private final Path directory;
	private final FileInventory fork;
	private final JsonMapper json;
	private final OverlayJson codec;
	private final Consumer<WriteStep> afterStep;

	/**
	 * Creates the overlays of a campaign.
	 *
	 * @param campaign the campaign storage directory
	 * @param fork the files recorded at the campaign's fork
	 * @param json the JSON mapper
	 * @throws NullPointerException if any argument is null
	 */
	public FileCandidateOverlays(Path campaign, FileInventory fork, JsonMapper json)
	{
		this(campaign, fork, json, _ ->
		{
		});
	}

	/**
	 * Creates the overlays of a campaign that report each completed write step.
	 *
	 * @param campaign the campaign storage directory
	 * @param fork the files recorded at the campaign's fork
	 * @param json the JSON mapper
	 * @param afterStep receives each step after it completes; an exception that it throws stops the write at that
	 *   point, as a crash would
	 * @throws NullPointerException if any argument is null
	 */
	public FileCandidateOverlays(Path campaign, FileInventory fork, JsonMapper json, Consumer<WriteStep> afterStep)
	{
		requireThat(campaign, "campaign").isNotNull();
		requireThat(fork, "fork").isNotNull();
		requireThat(afterStep, "afterStep").isNotNull();
		this.directory = campaign.resolve("overlays");
		this.fork = fork;
		this.json = json;
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
		byte[] content = Files.readAllBytes(file);
		Overlay overlay;
		try
		{
			JsonNode tree = json.readTree(content);
			overlay = codec.read(tree, fork);
		}
		catch (JacksonException e)
		{
			throw new IOException("The overlay file " + file + " is not valid JSON: " + e.getOriginalMessage(), e);
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			throw new IOException("The overlay file " + file + " does not describe an overlay on this campaign's " +
				"fork: " + e.getMessage(), e);
		}
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
		AtomicFiles.replace(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(codec.write(overlay)),
			afterStep);
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
