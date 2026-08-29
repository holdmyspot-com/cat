import assert from "node:assert/strict";
import {chmod, mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {spawn} from "node:child_process";
import test from "node:test";
import {fileURLToPath} from "node:url";

const directory = new URL("./", import.meta.url);
const captureSource = fileURLToPath(new URL("capture-source.mjs", directory));

test("capture supervisor records a completed runner turn", async () =>
{
  const fixture = await createFixture(`#!/usr/bin/env node
console.log(JSON.stringify({type: "started", runId: "test-run"}));
process.stdin.on("data", (chunk) => {
  const message = JSON.parse(chunk);
  if (message.type === "turn")
    console.log(JSON.stringify({type: "turn-completed"}));
  if (message.type === "complete")
    process.exit(0);
});
`);
  try
  {
    const result = await runCapture(fixture);

    assert.equal(result.exitCode, 0, result.stderr);
    const record = await readRecord(fixture);
    assert.equal(record.schemaVersion, 1);
    assert.equal(record.state, "completed");
    assert.equal(record.reason, "The runner recorded a completed turn and exited normally.");
    assert.equal(typeof record.processId, "number");
    assert.equal(typeof record.runnerProcessId, "number");
    assert.equal(record.runId, "test-run");
    assert.equal(typeof record.startedAt, "string");
    assert.equal(typeof record.finishedAt, "string");
    assert.equal(record.exitCode, 0);
    assert.equal(record.signal, null);
  }
  finally
  {
    await rm(fixture.root, {recursive: true, force: true});
  }
});

test("capture supervisor records a runner that ends before accepting a turn", async () =>
{
  const fixture = await createFixture("#!/usr/bin/env node\nprocess.exit(1);\n");
  try
  {
    const result = await runCapture(fixture);

    assert.notEqual(result.exitCode, 0, result.stderr);
    assert.equal((await readRecord(fixture)).state, "failed");
    assert.equal((await readRecord(fixture)).reason, "The runner ended before accepting a turn.");
  }
  finally
  {
    await rm(fixture.root, {recursive: true, force: true});
  }
});

async function createFixture(runnerSource)
{
  const root = await mkdtemp(join(tmpdir(), "capture-supervisor-test-"));
  const runner = join(root, "runner.mjs");
  const project = join(root, "project");
  const prompt = join(root, "prompt.txt");
  const output = join(root, "events.jsonl");
  const runRoot = join(root, "run");

  await writeFile(runner, runnerSource);
  await chmod(runner, 0o755);
  await mkdir(project);
  await mkdir(runRoot);
  await writeFile(prompt, "implement this\n");

  return {root, runner, project, prompt, output, runRoot};
}

function runCapture(fixture)
{
  return new Promise((resolve, reject) =>
  {
    const child = spawn(process.execPath, [captureSource, fixture.runner, "artifact", fixture.project, fixture.prompt,
      fixture.output, fixture.runRoot, "base"]);
    let stderr = "";
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.once("error", reject);
    child.once("exit", (exitCode, signal) => resolve({exitCode, signal, stderr}));
  });
}

async function readRecord(fixture)
{
  return JSON.parse(await readFile(join(fixture.runRoot, "capture-supervisor.json"), "utf8"));
}
