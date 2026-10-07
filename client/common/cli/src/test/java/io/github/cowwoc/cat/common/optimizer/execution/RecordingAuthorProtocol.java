package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.campaign.AuthorKind;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureKind;
import io.github.cowwoc.cat.common.optimizer.campaign.HunkEvidence;
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
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Optional;
import java.util.function.Function;

/**
 * An {@link AuthorProtocol} that records each brief and renders the production author prompt. By default, each author
 * reports that it needed no edit outside its authority.
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
			JsonMapper json = JsonMapper.builder().build();
			return new AuthorPrompt(json, new ProposalSchema(json), Set.of(DriverHarness.PROMPT), Set.of()).
				request(brief, copy);
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
			List<AuthorConflict> reportedConflicts = conflicts.apply(briefs.getLast());
			Set<String> conflicting = new HashSet<>();
			for (AuthorConflict conflict : reportedConflicts)
				conflicting.addAll(conflict.instances());
			List<String> guidance = new ArrayList<>();
			for (String identifier : briefs.getLast().failureIds())
			{
				if (!conflicting.contains(identifier))
					guidance.add(identifier);
			}
			List<HunkEvidence> evidence = new ArrayList<>();
			String dissimilar = briefs.getLast().contract().categories().getFirst().tests().getFirst().id();
			try
			{
				Path file = Path.of(Files.readString(result.effects().resolve("changed-path")));
				FileDelta delta = new FileDelta(file, new FileContent(Files.readAllBytes(
					result.effects().resolve("before"))), new FileContent(Files.readAllBytes(result.effects().resolve(file))),
					PosixFilePermissions.fromString("rw-r--r--"));
				for (Hunk hunk : Hunks.between(delta))
				{
					evidence.add(new HunkEvidence("hunk-" + (evidence.size() + 1), file, hunk.beforeStart(),
						hunk.beforeEnd(), hunk.afterStart(), hunk.afterEnd(), List.copyOf(briefs.getLast().failureIds()),
						"Another use requiring the same instruction", dissimilar));
				}
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
			List<AuthorKind> kinds = new ArrayList<>();
			if (!guidance.isEmpty())
			{
				kinds.add(new AuthorKind("missing instruction", guidance, FailureKind.GUIDANCE,
					evidence.stream().map(HunkEvidence::id).toList()));
			}
			if (!conflicting.isEmpty())
			{
				kinds.add(new AuthorKind("conflicting requirements", List.copyOf(conflicting), FailureKind.CONFLICT,
					List.of()));
			}
			return reports.apply(result).map(edits -> new AuthorReport(edits, new AuthorManifest(kinds, evidence),
				reportedConflicts));
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
