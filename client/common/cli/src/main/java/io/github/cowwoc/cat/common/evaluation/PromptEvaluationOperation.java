package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.ToolScope;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns the services that participate in one prompt-evaluation operation.
 *
 * <p>
 * The operation retains its caller's scope rather than allowing evaluator collaborators to create or retain a
 * process-wide JSON mapper. Command families move here as their mapper-bearing access contracts are made local.
 */
public final class PromptEvaluationOperation
{
	private final ToolScope scope;

	/**
	 * Creates services bound to one open prompt-evaluation scope.
	 *
	 * @param scope the scope that owns this operation's services
	 */
	public PromptEvaluationOperation(ToolScope scope)
	{
		this.scope = scope;
	}

	/**
	 * Returns this operation's JSON mapper.
	 *
	 * @return the operation-owned mapper
	 * @throws IllegalStateException if the owning scope is closed
	 */
	public JsonMapper json()
	{
		return scope.json();
	}
}
