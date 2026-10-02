package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureKind;
import io.github.cowwoc.cat.common.optimizer.inventory.Hunks;
import io.github.cowwoc.cat.common.optimizer.inventory.Hunk;
import io.github.cowwoc.cat.common.optimizer.inventory.FileDelta;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.execution.prompt.AuthorPrompt;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
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
	private Function<AuthorBrief, List<AuthorConflict>> conflicts = _ -> List.of();

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
			return new SessionRequest(SessionRole.AUTHOR, copy, List.of("Improve " + brief.cell()), List.of(),
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

	@Override
	public Optional<AuthorReport> report(SessionResult.Completed result)
	{
		synchronized (lock)
		{
			JsonMapper json = JsonMapper.builder().build();
			if (!result.responses().getLast().getLast().equals("done"))
				return new AuthorPrompt(json, new ProposalSchema(json), Set.of(), Set.of()).report(result);
			Map<String, FailureKind> classifications = new HashMap<>();
			for (String identifier : briefs.getLast().failureIds())
				classifications.put(identifier, FailureKind.GUIDANCE);
			List<AuthorConflict> reportedConflicts = conflicts.apply(briefs.getLast());
			for (AuthorConflict conflict : reportedConflicts)
			{
				for (String identifier : conflict.instances())
					classifications.put(identifier, FailureKind.CONFLICT);
			}
			List<AuthorManifest.Evidence> evidence = new ArrayList<>();
			try
			{
				Path file = Path.of(Files.readString(result.effects().resolve("changed-path")));
				FileDelta delta = new FileDelta(file, new FileContent(Files.readAllBytes(
					result.effects().resolve("before"))), new FileContent(Files.readAllBytes(result.effects().resolve(file))),
					PosixFilePermissions.fromString("rw-r--r--"));
				for (Hunk hunk : Hunks.between(delta))
				{
					evidence.add(new AuthorManifest.Evidence(file, hunk.beforeStart(), hunk.beforeEnd(),
						hunk.afterStart(), hunk.afterEnd(), List.copyOf(classifications.keySet()),
						"Another use requiring the same instruction", "A use outside the instruction's scope"));
				}
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
			return reports.apply(result).map(edits -> new AuthorReport(edits, new AuthorManifest(classifications,
				evidence), reportedConflicts));
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
	 * Supplies reported conflict evidence for the driver's validation and pause decision.
	 *
	 * @param script the conflict reports based on the supplied brief
	 */
	public void conflicts(Function<AuthorBrief, List<AuthorConflict>> script)
	{
		synchronized (lock)
		{
			conflicts = script;
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
