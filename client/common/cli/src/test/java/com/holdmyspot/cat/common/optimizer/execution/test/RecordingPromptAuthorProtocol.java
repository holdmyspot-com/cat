package com.holdmyspot.cat.common.optimizer.execution.test;

import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorKind;
import com.holdmyspot.cat.common.optimizer.campaign.FailureKind;
import com.holdmyspot.cat.common.optimizer.campaign.HunkEvidence;
import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorBrief;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorConflict;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorManifest;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorProtocol;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorReport;
import com.holdmyspot.cat.common.optimizer.execution.NeededEdit;
import com.holdmyspot.cat.common.optimizer.execution.prompt.PromptAuthorPrompt;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileDelta;
import com.holdmyspot.cat.common.optimizer.inventory.Hunk;
import com.holdmyspot.cat.common.optimizer.inventory.Hunks;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
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
 * A {@link PromptAuthorProtocol} that records each brief and renders the production prompt author's prompt. By default,
 * each prompt author reports that it needed no edit outside its authority.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class RecordingPromptAuthorProtocol implements PromptAuthorProtocol
{
	private final Object lock = new Object();
	private final List<PromptAuthorBrief> briefs = new ArrayList<>();
	private Function<SessionResult.Completed, Optional<List<NeededEdit>>> reports = _ -> Optional.of(List.of());
	private Function<PromptAuthorBrief, List<PromptAuthorConflict>> conflicts = _ -> List.of();

	/**
	 * Creates a protocol that has written no brief.
	 */
	public RecordingPromptAuthorProtocol()
	{
	}

	@Override
	public SessionRequest request(PromptAuthorBrief brief, Path copy)
	{
		synchronized (lock)
		{
			briefs.add(brief);
			JsonMapper json = JsonMapper.builder().build();
			return new PromptAuthorPrompt(json, new ProposalSchema(json), Set.of(DriverHarness.PROMPT), Set.of()).
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
	public Optional<PromptAuthorReport> report(SessionResult.Completed result)
	{
		synchronized (lock)
		{
			JsonMapper json = JsonMapper.builder().build();
			if (!result.responses().getLast().getLast().equals("done"))
				return new PromptAuthorPrompt(json, new ProposalSchema(json), Set.of(), Set.of()).report(result);
			List<PromptAuthorConflict> reportedConflicts = conflicts.apply(briefs.getLast());
			Set<String> conflicting = new HashSet<>();
			for (PromptAuthorConflict conflict : reportedConflicts)
				conflicting.addAll(conflict.failures());
			List<String> guidance = new ArrayList<>();
			for (String identifier : briefs.getLast().failureIds())
			{
				if (!conflicting.contains(identifier))
					guidance.add(identifier);
			}
			List<HunkEvidence> evidence = new ArrayList<>();
			String dissimilar = briefs.getLast().contract().tests().getFirst().id();
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
			List<PromptAuthorKind> kinds = new ArrayList<>();
			if (!guidance.isEmpty())
			{
				kinds.add(new PromptAuthorKind("missing instruction", guidance, FailureKind.GUIDANCE,
					evidence.stream().map(HunkEvidence::id).toList(), ""));
			}
			if (!conflicting.isEmpty())
			{
				kinds.add(new PromptAuthorKind("conflicting requirements", List.copyOf(conflicting),
					FailureKind.CONFLICT, List.of(), ""));
			}
			return reports.apply(result).map(edits -> new PromptAuthorReport(edits, new PromptAuthorManifest(kinds,
				evidence), reportedConflicts));
		}
	}

	/**
	 * Replaces the script that reads each prompt author's report of needed edits.
	 *
	 * @param script returns the needed edits of each completed prompt author session, or empty if its report is
	 *   malformed
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
	public void conflicts(Function<PromptAuthorBrief, List<PromptAuthorConflict>> script)
	{
		synchronized (lock)
		{
			conflicts = script;
		}
	}

	/**
	 * Returns the briefs of the prompt author sessions started so far.
	 *
	 * @return the briefs, in start order
	 */
	public List<PromptAuthorBrief> briefs()
	{
		synchronized (lock)
		{
			return List.copyOf(briefs);
		}
	}
}
