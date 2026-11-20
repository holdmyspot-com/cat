#!/usr/bin/env node
// Simulates the installed launcher protocol documented in the Harness Runners rule.
import assert from "node:assert/strict";
import {appendFile, mkdir, readFile, writeFile} from "node:fs/promises";
import {join} from "node:path";
import {createInterface} from "node:readline";
import {setTimeout} from "node:timers/promises";

const args = process.argv.slice(2);
if (args.includes("--artifact"))
{
  process.stderr.write("The installed runner rejects --artifact.\n");
  process.stdout.write('{"type":"started"}\n');
  for await (const line of createInterface({input: process.stdin}))
  {
    if (JSON.parse(line).type === "complete")
      break;
    process.stdout.write('{"type":"turn-completed"}\n');
  }
  process.exit(2);
}

const options = new Map();
for (let index = 0; index < args.length; index++)
{
  const name = args[index];
  const flag = name === "--install-cat" || name === "--session-transcripts";
  const value = flag ? true : args[++index];
  assert.equal(options.has(name), false);
  options.set(name, value);
}

const project = options.get("--project");
const results = options.get("--results");
const fixture = JSON.parse(await readFile(join(project, "fixture.json"), "utf8"));
assert.equal(options.get("--model"), fixture.model);
assert.equal(options.get("--effort"), fixture.effort);
assert.equal(await readFile("/dev/stdin", "utf8"), "");
const conversation = options.get("--conversation");
const input = options.get("--input");
const transcript = options.get("--output");
const keepsRollouts = options.has("--session-transcripts");
assert.deepEqual(new Set(options.keys()), new Set(["--project", "--results", "--model", "--effort",
  "--output", ...(conversation ? ["--conversation", "--install-cat"] : ["--input"]),
  ...(keepsRollouts ? ["--session-transcripts"] : [])]));

const sessionId = "observed-id";
const idRecord = {type: "session_id", session_id: sessionId};
const session = join(results, fixture.harness, sessionId);
const manifest = {schema_version: 6, harness: fixture.harness, id: sessionId, state: "completed",
  turn_number: 1, exit_code: 0};
const finalEvent = fixture.harness === "codex" ?
  '{ "method": "turn/completed", "params": {"threadId":"observed-id","turn":{"items":[{"type":"agentMessage","phase":"final_answer","text":"APPROVED"}]}} }' :
  '{ "type": "result", "result": "APPROVED" }';

async function accept(sessionDirectory, record)
{
  await mkdir(join(sessionDirectory, "project"), {recursive: true});
  await writeFile(join(sessionDirectory, "session.json"), JSON.stringify(record));
}

async function writeRecord(path, record)
{
  await appendFile(path, `${JSON.stringify(record)}\n`);
}

await accept(join(results, fixture.harness, "aaa-decoy"), {...manifest, id: "aaa-decoy"});
if (fixture.state === "refused")
{
  process.stderr.write("The requested session ended with status refused.\n");
  process.exit(2);
}

if (input)
{
  const records = (await readFile(input, "utf8")).trimEnd().split("\n").map(JSON.parse);
  assert.deepEqual(records, [{type: "turn", prompt: fixture.prompt}, {type: "complete"}]);
  await writeRecord(transcript, idRecord);
  await writeRecord(transcript, {type: "harness_event", event: finalEvent});
  await writeRecord(transcript, {type: "turn_completed", exit_code: 0, assistant_response: "APPROVED"});
  if (fixture.state !== "missing")
    await accept(session, manifest);
  process.exit(0);
}

const inbox = join(conversation, "to-child.jsonl");
const replies = join(conversation, "from-child.jsonl");
const events = join(conversation, "events.jsonl");
await mkdir(conversation, {recursive: true});
await writeFile(replies, "");
await writeFile(events, "");
await writeRecord(replies, idRecord);
if (fixture.state === "early")
  process.exit(0);

let delivered = 0;
async function nextMessage()
{
  for (let checks = 0; checks < 500; checks++)
  {
    let contents;
    try { contents = await readFile(inbox, "utf8"); }
    catch (error) { if (error.code !== "ENOENT") throw error; }
    const line = contents?.split("\n").slice(0, -1)[delivered];
    if (line)
    {
      delivered++;
      return JSON.parse(line);
    }
    await setTimeout(5);
  }
  throw new Error("The supervisor did not send the next conversation record.");
}

for (const turn of [1, 2])
{
  const message = await nextMessage();
  assert.deepEqual(Object.keys(message), ["turn"]);
  assert.ok(message.turn.includes("Return exactly `APPROVED`."));
  assert.ok(message.turn.includes(turn === 1 ? fixture.prompt.trim() : "required format"));
  const finding = "## Findings\n\n- **[CHANGE.md:1](CHANGE.md:1) Missing validation**\n" +
    "\t- **Evidence:** Équipe.\n\t- **Consequence:** Bad input is accepted.\n\t- **Correction:** Validate the input.";
  const historicalFinding = finding.replaceAll("\t", "  ");
  const parentResponse = turn === 1 ? "Unstructured review. Équipe." : "APPROVED";
  let response = parentResponse;
  if (turn === 2 && fixture.state === "changed-relay")
    response = historicalFinding;
  if (turn === 2 && fixture.state === "tab-findings")
    response = finding;
  if (fixture.harness === "codex")
  {
    const start = {method: "item/completed", params: {threadId: sessionId, item: {type: "subAgentActivity",
      kind: "started", agentThreadId: `review-${turn}`}}};
    await writeRecord(events, {type: "harness_event", event: JSON.stringify(start)});
    const review = {method: "item/completed", params: {threadId: `review-${turn}`,
      item: {type: "agentMessage", phase: "final_answer", text: response}}};
    await writeRecord(events, {type: "harness_event", event: JSON.stringify(review)});
  }
  else
  {
    const id = `agent-call-${turn}`;
    const role = fixture.state === "wrong-reviewer" ? "cat:claude:security-medium" : "cat:claude:architecture-medium";
    const prompt = fixture.state === "omitted-contract" ? "Review the change." :
      "# Review Contract\n\nReturn exactly `APPROVED`.";
    const call = {type: "assistant", parent_tool_use_id: null,
      message: {content: [{type: "tool_use", id, name: "Agent", input: {subagent_type: role, prompt}}]}};
    const start = {type: "system", subtype: "task_started", task_id: `review-${turn}`, tool_use_id: id,
      subagent_type: role};
    const reply = {type: "user", parent_tool_use_id: null,
      message: {content: [{type: "tool_result", tool_use_id: id}]},
      tool_use_result: {status: "completed", agentId: `review-${turn}`, content: [{type: "text", text: response}]}};
    for (const event of [call, start, reply])
      await writeRecord(events, {type: "harness_event", event: JSON.stringify(event)});
  }
  const terminalResponse = fixture.state === "tab-findings" ? response : parentResponse;
  if (terminalResponse === "APPROVED")
    await writeRecord(events, {type: "harness_event", event: finalEvent});
  else
  {
    const event = fixture.harness === "codex" ?
      {method: "turn/completed", params: {threadId: sessionId, turn: {items: [{
        type: "agentMessage", phase: "final_answer", text: terminalResponse}]}}} :
      {type: "result", result: terminalResponse};
    await writeRecord(events, {type: "harness_event", event: JSON.stringify(event)});
  }
  await writeRecord(replies, {type: "turn_completed", exit_code: 0, assistant_response: terminalResponse});
  await writeRecord(replies, {turn_end: turn, status: "completed"});
}
assert.deepEqual(await nextMessage(), {end: true});
if (fixture.state !== "missing")
  await accept(session, {...manifest, turn_number: 2});
if (keepsRollouts)
{
  const rollouts = join(session, "artifacts", "session-transcripts");
  const role = fixture.state === "wrong-reviewer" ? "cat-security-medium" : "cat-architecture-medium";
  await mkdir(rollouts, {recursive: true});
  for (const turn of [1, 2])
  {
    const id = `review-${turn}`;
    const spawn = {parent_thread_id: sessionId, depth: 1, agent_role: role};
    await writeRecord(join(rollouts, `rollout-${id}.jsonl`), {type: "session_meta",
      payload: {id, source: {subagent: {thread_spawn: spawn}}, agent_role: role}});
  }
}
await writeRecord(replies, {ended: "completed"});
