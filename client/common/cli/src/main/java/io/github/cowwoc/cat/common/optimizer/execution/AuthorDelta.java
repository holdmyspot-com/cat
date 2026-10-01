package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.TargetChange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Builds the candidate of an author attempt from the attempt's validated target changes.
 */
final class AuthorDelta
{
	/**
	 * Prevents instantiation.
	 */
	private AuthorDelta()
	{
	}

	/**
	 * Applies an author attempt's target changes to the working candidate.
	 *
	 * @param working the working candidate's overlay
	 * @param copy the author's copy, which holds the new content of each written target
	 * @param changes the permitted target changes of the attempt's review
	 * @return the candidate, or empty if a file in the copy no longer holds the content that the review recorded
	 * @throws IOException if a file in the copy cannot be read
	 */
	static Optional<Overlay> apply(Overlay working, Path copy, List<TargetChange> changes)
		throws IOException
	{
		Overlay candidate = working;
		for (TargetChange change : changes)
		{
			switch (change)
			{
				case TargetChange.TargetWrite write ->
				{
					FileContent content = new FileContent(Files.readAllBytes(copy.resolve(write.source())));
					if (!content.hash().equals(write.after().contentHash()))
						return Optional.empty();
					candidate = candidate.write(write.target(), content, write.after().permissions());
				}
				case TargetChange.TargetDelete delete -> candidate = candidate.delete(delete.target());
			}
		}
		return Optional.of(candidate);
	}
}
