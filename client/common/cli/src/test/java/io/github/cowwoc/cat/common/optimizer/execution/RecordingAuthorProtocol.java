package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * An {@link AuthorProtocol} that records each brief and asks the author to improve the brief's category.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class RecordingAuthorProtocol implements AuthorProtocol
{
	private final Object lock = new Object();
	private final List<AuthorBrief> briefs = new ArrayList<>();

	/**
	 * Creates a protocol that has written no brief.
	 */
	public RecordingAuthorProtocol()
	{
	}

	@Override
	public SessionRequest request(AuthorBrief brief, Path copy)
	{
		synchronized (lock)
		{
			briefs.add(brief);
			return new SessionRequest(SessionRole.AUTHOR, copy, List.of("Improve " + brief.category()), List.of(),
				Duration.ofMinutes(10));
		}
	}

	/**
	 * Returns the briefs of the author sessions started so far.
	 *
	 * @return the briefs, in start order
	 */
	public List<AuthorBrief> briefs()
	{
		synchronized (lock)
		{
			return List.copyOf(briefs);
		}
	}
}
