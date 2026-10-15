package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileDelta;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.OverlayEntry.Write;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.TargetChange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Builds the candidate of a prompt author attempt from the attempt's validated target changes.
 */
final class PromptAuthorDelta
{
	/**
	 * Prevents instantiation.
	 */
	private PromptAuthorDelta()
	{
	}

	/**
	 * Applies a prompt author attempt's target changes to the working candidate.
	 *
	 * @param working the working candidate's overlay
	 * @param effects the session's effects directory, which holds each written target's new content
	 * @param changes the permitted target changes of the attempt's review
	 * @return the candidate, or empty if an effects file no longer holds the content that the review recorded
	 * @throws IOException if an effects file cannot be read
	 */
	static Optional<Overlay> apply(Overlay working, Path effects, List<TargetChange> changes)
		throws IOException
	{
		Overlay candidate = working;
		for (TargetChange change : changes)
		{
			switch (change)
			{
				case TargetChange.TargetWrite write ->
				{
					FileContent content = new FileContent(Files.readAllBytes(effects.resolve(write.source())));
					if (!content.hash().equals(write.after().contentHash()))
						return Optional.empty();
					candidate = candidate.write(write.target(), content, write.after().permissions());
				}
				case TargetChange.TargetDelete delete -> candidate = candidate.delete(delete.target());
			}
		}
		return Optional.of(candidate);
	}
	/**
	 * Captures both sides of the validated changes before the authoring copy is deleted.
	 *
	 * @param working the starting overlay
	 * @param copy the unchanged authoring source
	 * @param effects the prompt author's edited files
	 * @param changes the permitted resolved changes
	 * @return the original file deltas, or empty if an effects file no longer matches its inventory
	 * @throws IOException if a file cannot be read or differs from its recorded inventory
	 */
	static Optional<List<FileDelta>> changes(Overlay working, Path copy, Path effects, List<TargetChange> changes)
		throws IOException
	{
		List<FileDelta> files = new ArrayList<>();
		for (TargetChange change : changes)
		{
			FileContent before = before(working, copy, change.target());
			switch (change)
			{
				case TargetChange.TargetWrite write ->
				{
					FileContent after = new FileContent(Files.readAllBytes(effects.resolve(write.source())));
					if (!after.hash().equals(write.after().contentHash()))
						return Optional.empty();
					files.add(new FileDelta(write.target(), before, after, write.after().permissions()));
				}
				case TargetChange.TargetDelete delete ->
				{
					RegularFile file = (RegularFile) working.snapshot().entries().get(delete.target());
					files.add(new FileDelta(delete.target(), before, null, file.permissions()));
				}
			}
		}
		return Optional.of(List.copyOf(files));
	}

	/**
	 * Reads a target's working bytes from its overlay or an unchanged dereferenced copy.
	 *
	 * @param working the starting overlay
	 * @param copy the authoring source
	 * @param target the resolved file
	 * @return its content, or {@code null} for a created file
	 * @throws IOException if a recorded file cannot be read or its bytes changed
	 */
	private static FileContent before(Overlay working, Path copy, Path target) throws IOException
	{
		if (working.entries().get(target) instanceof Write write)
			return write.content();
		RegularFile expected = (RegularFile) working.snapshot().entries().get(target);
		if (expected == null)
			return null;
		for (Path path : working.snapshot().entries().keySet())
		{
			if (path.isAbsolute() || !working.snapshot().resolve(path).orElse(path).equals(target))
				continue;
			Path source = copy.resolve(path);
			if (!Files.isRegularFile(source))
				continue;
			FileContent content = new FileContent(Files.readAllBytes(source));
			if (!content.hash().equals(expected.contentHash()))
				throw new IOException("The working bytes changed while reading " + PortablePaths.text(target));
			return content;
		}
		throw new IOException("No authoring source holds the working bytes of " + PortablePaths.text(target));
	}
}
