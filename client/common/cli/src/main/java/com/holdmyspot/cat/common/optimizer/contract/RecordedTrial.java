package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryDiff;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Replies, activity and project snapshots as the assertion tools see them.
 * <p>
 * Each read of a project snapshot sees the files as they are at the time of the read.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public class RecordedTrial implements TrialRecord
{
	private final List<List<String>> responses;
	private final SessionEvidence activity;
	private final Path initialProject;
	private final Path effects;

	/**
	 * Creates a record from the data that an assertion tool can read.
	 *
	 * @param responses      the ordered messages of each turn
	 * @param activity       the normalized activity
	 * @param initialProject the project before the first turn
	 * @param effects        the final project
	 * @throws NullPointerException if an argument, a response list or a message is null
	 */
	protected RecordedTrial(List<List<String>> responses, SessionEvidence activity, Path initialProject, Path effects)
	{
		requireThat(responses, "responses").isNotNull();
		requireThat(activity, "activity").isNotNull();
		requireThat(initialProject, "initialProject").isNotNull();
		requireThat(effects, "effects").isNotNull();
		this.responses = responses.stream().map(List::copyOf).toList();
		this.activity = activity;
		this.initialProject = initialProject;
		this.effects = effects;
	}

	/**
	 * Returns the full activity to a record that presents evidence to the judge.
	 *
	 * @return the normalized activity
	 */
	protected final SessionEvidence activity()
	{
		return activity;
	}

	@Override
	public final List<List<String>> replies()
	{
		return responses;
	}

	@Override
	public final List<SessionEvidence.Tool> tools()
	{
		List<SessionEvidence.Tool> tools = new ArrayList<>();
		for (SessionEvidence.Entry entry : activity.entries())
		{
			if (entry instanceof SessionEvidence.Tool tool)
				tools.add(tool);
		}
		return tools;
	}

	@Override
	public final List<String> received()
	{
		return activity.received();
	}

	@Override
	public final List<String> changedPaths() throws IOException
	{
		FileInventory before = InventoryScanner.scan(initialProject, Set.of());
		FileInventory after = InventoryScanner.scan(effects, Set.of());
		return InventoryDiff.between(before, after).changes().stream().map(change ->
			PortablePaths.text(change.path())).toList();
	}

	@Override
	public final boolean exists(String path) throws IOException
	{
		requireThat(path, "path").isNotNull();
		return Files.exists(effects.resolve(path), LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * A file whose real location lies outside the project copy, such as one reached through a link that the trial
	 * created, is not a file of the project. Bytes that are not valid UTF-8 are replaced, so that the tool observes
	 * them instead of failing to read the file.
	 */
	@Override
	public final Optional<String> content(String path) throws IOException
	{
		requireThat(path, "path").isNotNull();
		return read(effects, path);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * A file whose real location lies outside the copy of the project as it was when the trial started is not a file
	 * of that copy. Bytes that are not valid UTF-8 are replaced.
	 */
	@Override
	public final Optional<String> initial(String path) throws IOException
	{
		requireThat(path, "path").isNotNull();
		return read(initialProject, path);
	}

	/**
	 * Reads a regular file of a directory.
	 *
	 * @param directory the directory
	 * @param path      the path relative to the directory
	 * @return the file's text, or empty if the path is not a regular file whose real location lies in the directory
	 * @throws IOException if the file cannot be read
	 */
	private static Optional<String> read(Path directory, String path) throws IOException
	{
		Path file = directory.resolve(path);
		if (!Files.isRegularFile(file))
			return Optional.empty();
		if (!file.toRealPath().startsWith(directory.toRealPath()))
			return Optional.empty();
		return Optional.of(new String(Files.readAllBytes(file), UTF_8));
	}
}
