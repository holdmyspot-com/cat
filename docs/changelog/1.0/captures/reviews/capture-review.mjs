import {appendFile, mkdir, open, readFile, writeFile} from "node:fs/promises";
import {isAbsolute, join} from "node:path";
import {hasCodexReviewer, observeClaudeDelegation, observeCodexDelegation} from "./review-delegation.mjs";
import {delegationPrompt, reviewFormat} from "./review-format.mjs";
import {validReviewResponse} from "./review-response.mjs";
import {readRecords, startRunner} from "./review-runner.mjs";
import {completedSession, recordCapture} from "./review-session.mjs";

const transcriptName = "transcript.jsonl";
const inputName = "input.jsonl";
const conversationName = "conversation";
const conversationInbox = "to-child.jsonl";
const conversationReplies = "from-child.jsonl";
const conversationEvents = "events.jsonl";
const correctionPrompt = "The reviewer response you just relayed did not meet its required format. Delegate a fresh " +
  "review to the same installed CAT reviewer, include the complete Review Contract, and return the response verbatim.";

/**
 * Rejects incomplete arguments and resolves the paths and profile passed by the matrix.
 */
function captureOptions(options, requiredPaths)
{
  const {runner, project, promptFile, output, results, model, effort} = options;
  const paths = [runner, project, promptFile, output, results, ...requiredPaths];
  if (paths.some((path) => !path || !isAbsolute(path)) || !model?.trim() || !effort?.trim())
    throw new Error("Supply absolute runner, project, prompt, output, and results paths, plus a model and effort. " +
      "A CAT review also needs the absolute installed artifact path.");
  const transcript = join(results, transcriptName);
  return {...options, transcript};
}

/**
 * Returns the runner arguments shared by Base and CAT captures, excluding their input transport.
 */
function runnerArguments({project, results, model, effort, transcript})
{
  return ["--project", project, "--results", results, "--model", model, "--effort", effort, "--output", transcript];
}

/**
 * Records the first launcher-reported identity and rejects a contradictory identity.
 */
function observeSessionId(sessionId, record)
{
  if (record.type !== "session_id")
    return sessionId;
  if (typeof record.session_id !== "string" || !record.session_id.trim())
    throw new Error("The launcher emitted a session_id record without a session ID");
  if (sessionId !== undefined && record.session_id !== sessionId)
    throw new Error("The launcher emitted two different session IDs");
  return record.session_id;
}

/**
 * Writes native event lines unchanged and identifies the parent reply and its delegated review evidence.
 */
async function retainNativeEvents(records, output, {harness, sessionId, selectedReviewer, reviewContract, start})
{
  const file = await open(output, start === undefined ? "wx" : "a");
  const reviewers = new Map();
  let response;
  let position = start ?? 0;
  try
  {
    for await (const {record, position: nextPosition} of records)
    {
      position = nextPosition;
      sessionId = observeSessionId(sessionId, record);
      if (record.type !== "harness_event")
        continue;
      if (typeof record.event !== "string")
        throw new Error("The launcher emitted a harness_event record without native event text");
      await file.writeFile(`${record.event}\n`);
      const event = JSON.parse(record.event);
      if (harness === "codex")
      {
        const parentTurn = event.method === "turn/completed" && event.params?.threadId === sessionId;
        if (parentTurn)
        {
          response = event.params.turn?.items?.findLast((item) =>
            item.type === "agentMessage" && item.phase === "final_answer")?.text;
        }
        if (selectedReviewer)
          observeCodexDelegation(reviewers, sessionId, event);
      }
      else
      {
        if (event.type === "result" && event.parent_tool_use_id == null)
          response = event.result;
        if (selectedReviewer)
          observeClaudeDelegation(reviewers, selectedReviewer, reviewContract, event);
      }
    }
  }
  finally
  {
    await file.close();
  }
  const relayedReviewers = [];
  if (validReviewResponse(response))
  {
    for (const [id, reply] of reviewers)
      if (reply === response)
        relayedReviewers.push(id);
  }
  return {sessionId, response, relayedReviewers, position};
}

/**
 * Captures one neutral Base turn and records its model profile after verifying the results copy.
 */
export async function captureBaseReview(harness, args)
{
  const [runner, project, promptFile, output, results, model, effort] = args;
  const options = captureOptions({runner, project, promptFile, output, results, model, effort}, []);
  const prompt = await readFile(promptFile, "utf8");
  const input = join(results, inputName);
  await writeFile(input, `${JSON.stringify({type: "turn", prompt})}\n{"type":"complete"}\n`, {flag: "wx"});
  const launch = startRunner(runner, [...runnerArguments(options), "--input", input]);
  await launch.requireSuccess();
  const evidence = await retainNativeEvents(readRecords(options.transcript), output, {harness});
  if (typeof evidence.response !== "string" || !evidence.response.trim())
    throw new Error(`${options.transcript} has no ${harness} terminal response`);
  await recordCapture(results, harness, evidence.sessionId, model, effort);
}

/**
 * Supervises a CAT review until its one allowed correction and an explicit conversation end have settled.
 */
export async function captureCatReview(harness, args)
{
  const [runner, artifact, project, promptFile, output, results, reviewer, model, effort] = args;
  const options = captureOptions({runner, project, promptFile, output, results, model, effort}, [artifact]);
  if (!reviewer?.trim())
    throw new Error("Supply the installed CAT reviewer name for a CAT review.");
  const request = await readFile(promptFile, "utf8");
  const reviewContract = await reviewFormat(artifact);
  const directory = join(results, conversationName);
  const inbox = join(directory, conversationInbox);
  await mkdir(directory);
  const installedReviewer = harness === "codex" ? `cat-${reviewer}` : `cat:claude:${reviewer}`;
  const initialPrompt = `${request.trim()}\n\nDelegate this task to the installed ${installedReviewer} reviewer ` +
    "in the foreground and return its response verbatim.";
  await sendConversation(inbox, {turn: delegationPrompt(initialPrompt, reviewContract)});

  const argsForRunner = [...runnerArguments(options), "--conversation", directory, "--install-cat"];
  if (harness === "codex")
    argsForRunner.push("--session-transcripts");
  const launch = startRunner(runner, argsForRunner);
  const replies = readRecords(join(directory, conversationReplies), {launch});
  const eventsFile = join(directory, conversationEvents);
  let sessionId;
  let endSent = false;
  try
  {
    sessionId = await awaitTurn(replies, launch, 1, sessionId);
    const eventContext = {harness, sessionId, selectedReviewer: installedReviewer, reviewContract};
    let evidence = await retainNativeEvents(readRecords(eventsFile), output, eventContext);
    let corrections = 0;
    if (evidence.relayedReviewers.length === 0)
    {
      const correction = `${correctionPrompt} Use the installed ${installedReviewer} reviewer in the foreground.`;
      await sendConversation(inbox, {turn: delegationPrompt(correction, reviewContract)});
      corrections = 1;
      sessionId = await awaitTurn(replies, launch, 2, sessionId);
      const start = evidence.position;
      evidence = await retainNativeEvents(readRecords(eventsFile, {start}), output, {...eventContext, sessionId, start});
    }

    await sendConversation(inbox, {end: true});
    endSent = true;
    let ended;
    for await (const {record} of replies)
    {
      if (Object.hasOwn(record, "ended"))
        ended = record.ended;
    }
    await launch.requireSuccess();
    if (ended !== "completed")
      throw new Error(`The ${harness} conversation has no completed terminal record`);
    const start = evidence.position;
    await retainNativeEvents(readRecords(eventsFile, {start}), output, {harness, sessionId, start});
    let accepted = evidence.relayedReviewers.length > 0;
    if (harness === "codex")
    {
      if (accepted)
      {
        const manifest = await completedSession(results, harness, sessionId);
        accepted = await hasCodexReviewer(manifest, sessionId, evidence.relayedReviewers, installedReviewer);
      }
      await writeFile(`${output}.validation.json`, `${JSON.stringify({accepted, corrections})}\n`, {flag: "wx"});
    }
    if (!accepted)
      throw new Error(`The ${harness} reviewer response did not satisfy the Review Contract after ${corrections} correction. ` +
        `Capture requires a verbatim reply from ${installedReviewer}.`);
    await recordCapture(results, harness, sessionId, model, effort);
  }
  finally
  {
    await replies.return();
    if (!endSent && !launch.finished)
    {
      try
      {
        await sendConversation(inbox, {end: true});
      }
      catch
      {
        launch.stop();
      }
    }
    await launch.closed;
  }
}

/**
 * Appends a conversation message for the requesting launcher's next read.
 */
async function sendConversation(inbox, message)
{
  await appendFile(inbox, `${JSON.stringify(message)}\n`);
}

/**
 * Reads the expected turn's end and returns any session ID already observed in the conversation.
 */
async function awaitTurn(replies, launch, turn, sessionId)
{
  let reply;
  for (let next = await replies.next(); !next.done; next = await replies.next())
  {
    const {record} = next.value;
    sessionId = observeSessionId(sessionId, record);
    if (record.type === "turn_completed" || record.type === "turn_failed")
      reply = record;
    if (Object.hasOwn(record, "ended"))
      break;
    if (Object.hasOwn(record, "turn_end"))
    {
      if (record.turn_end !== turn || record.status !== "completed" || reply?.exit_code !== 0)
        throw new Error(`Review turn ${turn} did not complete successfully. ${reply?.error ?? ""}`.trim());
      return sessionId;
    }
  }
  await launch.requireSuccess();
  throw new Error(`The runner exited before completing turn ${turn}`);
}
