package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * An {@link AuthorProtocol} that records each brief and asks the author to improve the brief's category. By default,
 * each author reports that it needed no edit outside its authority.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class RecordingAuthorProtocol implements AuthorProtocol
{
	private final Object lock = new Object();
	private final List<AuthorBrief> briefs = new ArrayList<>();
	private Function<SessionResult.Completed, Optional<List<NeededEdit>>> reports = _ -> Optional.of(List.of());

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
				Duration.ofMinutes(10), brief.contract().judge().profile());
		}
	}

	@Override
	public Optional<List<NeededEdit>> neededEdits(SessionResult.Completed result)
	{
		synchronized (lock)
		{
			return reports.apply(result);
		}
	}

	/**
	 * Replaces the script that reads each author's report of needed edits.
	 *
	 * @param script returns the needed edits of each completed author session, or empty if its report is malformed
	 */
	public void reports(Function<SessionResult.Completed, Optional<List<NeededEdit>>> script)
	{
		synchronized (lock)
		{
			this.reports = script;
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
