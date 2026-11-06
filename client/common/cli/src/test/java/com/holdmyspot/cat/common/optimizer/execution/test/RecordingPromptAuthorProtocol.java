package com.holdmyspot.cat.common.optimizer.execution.test;

import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorBrief;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorProtocol;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorReport;
import com.holdmyspot.cat.common.optimizer.execution.prompt.PromptAuthorPrompt;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link PromptAuthorProtocol} that records each brief that the driver supplies. The production
 * {@link PromptAuthorPrompt} builds every request and reads every report, so a test supplies a prompt author's reply as
 * the final response of its session, as {@link PromptAuthorReplies} writes it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class RecordingPromptAuthorProtocol implements PromptAuthorProtocol
{
	private final Object lock = new Object();
	private final List<PromptAuthorBrief> briefs = new ArrayList<>();
	private final PromptAuthorPrompt prompt;

	/**
	 * Creates a protocol that has recorded no brief.
	 */
	public RecordingPromptAuthorProtocol()
	{
		JsonMapper json = JsonMapper.builder().build();
		this.prompt = new PromptAuthorPrompt(json, new ProposalSchema(json), Set.of(DriverHarness.PROMPT), Set.of());
	}

	@Override
	public SessionRequest request(PromptAuthorBrief brief, Path copy)
	{
		synchronized (lock)
		{
			briefs.add(brief);
		}
		return prompt.request(brief, copy);
	}

	@Override
	public Optional<PromptAuthorReport> report(SessionResult.Completed result)
	{
		return prompt.report(result);
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
