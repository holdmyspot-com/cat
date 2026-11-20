import {readdir} from "node:fs/promises";
import {dirname, join} from "node:path";
import {readRecords} from "./review-runner.mjs";

/**
 * Tracks direct Codex reviewer threads and their final replies from app-server activity.
 */
export function observeCodexDelegation(reviewers, rootThread, event)
{
  if (event.method !== "item/completed")
    return;
  const {threadId, item} = event.params;
  if (threadId === rootThread && item.type === "subAgentActivity" && item.kind === "started" && item.agentThreadId)
    reviewers.set(item.agentThreadId, undefined);
  if (reviewers.has(threadId) && item.type === "agentMessage" && item.phase === "final_answer")
    reviewers.set(threadId, item.text);
}

/**
 * Tracks Claude's selected direct Agent call and the reply that call delivered to its parent.
 */
export function observeClaudeDelegation(reviewers, selectedReviewer, reviewContract, event)
{
  const content = event.message?.content;
  if (event.type === "assistant" && event.parent_tool_use_id == null && Array.isArray(content))
  {
    for (const block of content)
    {
      if (block.type === "tool_use" && block.name === "Agent" && block.input?.subagent_type === selectedReviewer &&
        typeof block.input.prompt === "string" && block.input.prompt.includes(reviewContract))
        reviewers.set(block.id, undefined);
    }
  }
  if (event.type === "user" && event.parent_tool_use_id == null && Array.isArray(content))
  {
    for (const block of content)
    {
      if (block.type !== "tool_result" || block.is_error || !reviewers.has(block.tool_use_id))
        continue;
      const result = event.tool_use_result;
      if (result?.status === "completed" && Array.isArray(result.content))
      {
        const text = result.content.filter((entry) => entry.type === "text").map((entry) => entry.text).join("\n");
        reviewers.set(block.tool_use_id, text);
      }
    }
  }
}

/**
 * Indicates whether a directly spawned Codex thread that supplied the relayed reply ran the selected role.
 * The public launcher copies these native rollouts to the results folder when the conversation ends.
 */
export async function hasCodexReviewer(manifest, rootThread, candidateThreads, selectedReviewer)
{
  const rollouts = join(dirname(manifest), "artifacts", "session-transcripts");
  for (const entry of await readdir(rollouts, {withFileTypes: true}))
  {
    if (!entry.isFile() || !entry.name.endsWith(".jsonl"))
      continue;
    const path = join(rollouts, entry.name);
    for await (const {record} of readRecords(path))
    {
      const metadata = record.payload;
      if (record.type === "session_meta" && candidateThreads.includes(metadata?.id))
      {
        const spawn = metadata.source?.subagent?.thread_spawn;
        if (spawn?.parent_thread_id === rootThread && spawn.depth === 1 && spawn.agent_role === selectedReviewer)
          return true;
      }
      break;
    }
  }
  return false;
}
