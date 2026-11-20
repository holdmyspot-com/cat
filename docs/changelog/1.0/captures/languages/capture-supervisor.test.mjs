import assert from "node:assert/strict";
import {chmod, mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {captureSource} from "./capture-source.mjs";

const sessionId = "expected-session";
const nativeEvents = ['{  "z": 1, "a": "café" }', '{ "item": "final answer" }'];
const task = "implement this\n";

test("the source capture gives the installed runner a records file and exports unchanged native events", async (t) =>
{
  const fixture = await createFixture(t);
  const result = await runCapture(fixture, "cat");

  assert.equal(result.sessionId, sessionId);
  const request = JSON.parse(await readFile(join(fixture.resultsFolder, "observed-request.json"), "utf8"));
  assert.deepEqual(request, {
    model: "provided-model",
    effort: "medium",
    installCat: true,
    records: [{type: "turn", prompt: task}, {type: "complete"}]
  });
  assert.equal(await readFile(fixture.output, "utf8"), nativeEvents.join("\n") + "\n");
  const record = await readRecord(fixture);
  assert.equal(record.schema_version, 3);
  assert.equal(record.state, "completed");
  assert.equal(typeof record.runner_process_id, "number");
  assert.equal(record.session_id, sessionId);
  assert.equal(record.exit_code, 0);
});

test("a missing copy for the emitted session id cannot publish an unrelated completed session", async (t) =>
{
  const fixture = await createFixture(t, "decoy");
  await assert.rejects(runCapture(fixture), /expected-session.*session\.json/);
  assert.equal((await readRecord(fixture)).state, "failed");
});

test("a mismatched manifest identity cannot publish the emitted session", async (t) =>
{
  const fixture = await createFixture(t, "mismatch");
  await assert.rejects(runCapture(fixture), /Manifest identity does not match codex session expected-session/);
  assert.equal((await readRecord(fixture)).state, "failed");
});

test("a nonzero runner exit causes a failed capture even when a completed copy exists", async (t) =>
{
  const fixture = await createFixture(t, "refused");
  await assert.rejects(runCapture(fixture), /request server refused the launch/);
  const record = await readRecord(fixture);
  assert.equal(record.state, "failed");
  assert.equal(record.exit_code, 2);
});

test("a zero runner exit without a completed turn cannot publish a completed manifest", async (t) =>
{
  const fixture = await createFixture(t, "no_turn");
  await assert.rejects(runCapture(fixture), /Source runner returned no successful completed turn/);
  assert.equal((await readRecord(fixture)).state, "failed");
});

// The stand-in writes the CAT transcript and copy described by harness-runners.md. The caller must submit a
// records file, preserve each native event and accept only the copy identified by the transcript's session_id.
async function createFixture(t, outcome = "completed")
{
  const root = await mkdtemp(join(tmpdir(), "capture-supervisor-test-"));
  t.after(() => rm(root, {recursive: true, force: true}));
  const runner = join(root, "runner.mjs");
  const project = join(root, "project");
  const prompt = join(root, "prompt.txt");
  const output = join(root, "events.jsonl");
  const resultsFolder = join(root, "results");

  await writeFile(runner, `#!/usr/bin/env node
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {mkdir, readFile, writeFile} from "node:fs/promises";
import {join} from "node:path";
import {parseArgs} from "node:util";

const {values} = parseArgs({options: {
  project: {type: "string"}, input: {type: "string"}, output: {type: "string"}, results: {type: "string"},
  model: {type: "string"}, effort: {type: "string"}, "install-cat": {type: "boolean"}
}});
assert.equal(readFileSync(0, "utf8"), "");
const records = (await readFile(values.input, "utf8")).trimEnd().split("\\n").map(JSON.parse);
await writeFile(join(values.results, "observed-request.json"), JSON.stringify({
  model: values.model, effort: values.effort, installCat: values["install-cat"] ?? false, records
}));
const id = ${JSON.stringify(sessionId)};
const events = ${JSON.stringify(nativeEvents)};
const outcome = ${JSON.stringify(outcome)};
const sessionFolder = join(values.results, "codex", outcome === "decoy" ? "decoy-session" : id);
await mkdir(sessionFolder, {recursive: true});
await writeFile(join(sessionFolder, "session.json"), JSON.stringify({
  id: ["decoy", "mismatch"].includes(outcome) ? "decoy-session" : id,
  harness: "codex", state: "completed", exit_code: 0, turn_number: 1
}));
await writeFile(values.output, [
  {type: "started"}, {type: "session_id", session_id: id},
  ...events.map((event) => ({type: "harness_event", event})),
  ...(outcome === "no_turn" ? [] : [
    {type: "turn_completed", exit_code: 0, error: "", assistant_response: "A final answer", assistant_messages: []}
  ])
].map(JSON.stringify).join("\\n") + "\\n");
if (outcome === "refused") {
  console.error("request server refused the launch");
  process.exitCode = 2;
}
`);
  await chmod(runner, 0o755);
  await mkdir(project);
  await mkdir(resultsFolder);
  await writeFile(prompt, task);

  return {runner, project, prompt, output, resultsFolder};
}

function runCapture(fixture, mode = "base")
{
  const {runner, project, prompt, output, resultsFolder} = fixture;
  return captureSource({runner, harness: "codex", project, promptFile: prompt, output, resultsFolder, mode,
    model: "provided-model", effort: "medium"}, 5);
}

async function readRecord(fixture)
{
  return JSON.parse(await readFile(join(fixture.resultsFolder, "capture-supervisor.json"), "utf8"));
}
