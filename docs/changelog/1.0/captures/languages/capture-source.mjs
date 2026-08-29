#!/usr/bin/env node
// Retains one source-generation turn until the isolated runner records its terminal event.
import {appendFile, readFile, rename, writeFile} from "node:fs/promises";
import {join} from "node:path";
import {spawn} from "node:child_process";
import {constants} from "node:os";
import {createInterface} from "node:readline";

const [runner, artifact, project, promptFile, output, runRoot, mode] = process.argv.slice(2);
if ([runner, artifact, project, promptFile, output, runRoot, mode].some((value) => !value?.trim()) ||
  !["base", "cat"].includes(mode))
  throw new Error("Usage: capture-source.mjs <runner> <artifact> <project> <prompt-file> <output> <run-root> <base|cat>");

const prompt = await readFile(promptFile, "utf8");
await writeFile(output, "");
const arguments_ = ["--artifact", artifact, "--project", project, "--run-root", runRoot, "--keep-run"];
if (mode === "cat")
  arguments_.push("--install-cat");
const child = spawn(runner, arguments_, {stdio: ["pipe", "pipe", "pipe"]});
const supervisorRecord = join(runRoot, "capture-supervisor.json");
const startedAt = new Date().toISOString();
let runId;

await writeSupervisorRecord("running", "The supervisor accepted the capture and is awaiting a terminal runner result.");

process.once("SIGTERM", () =>
{
  void writeSupervisorRecord("interrupted", "The supervisor received SIGTERM before recording a terminal result.").
    finally(() =>
    {
      child.kill("SIGTERM");
      process.exit(143);
    });
});

const keepAlive = setInterval(() => {}, 1_000);
let timedOut = false;
const timeoutSeconds = Number.parseInt(process.env.CAPTURE_TIMEOUT_SECONDS ?? "600", 10);
if (!Number.isSafeInteger(timeoutSeconds) || timeoutSeconds < 1)
  throw new Error("CAPTURE_TIMEOUT_SECONDS must be a positive whole number");
// A bounded capture keeps one failed harness from serializing the matrix. The supervisor retains its events as
// diagnostic evidence and schedules the other isolated captures independently.
const deadline = setTimeout(() =>
{
  timedOut = true;
  child.kill("SIGTERM");
}, timeoutSeconds * 1_000);
const forcedTermination = setTimeout(() =>
{
  if (timedOut && child.exitCode === null && child.signalCode === null)
    child.kill("SIGKILL");
}, (timeoutSeconds + 15) * 1_000);
let error = "";
let started;
let completed;
let completion;
let writes = Promise.resolve();
const ready = new Promise((resolve) => { started = resolve; });
const terminal = new Promise((resolve) => { completion = resolve; });
const exited = new Promise((resolve) => child.once("exit", (exitCode, signal) => resolve({exitCode, signal})));

createInterface({input: child.stdout}).on("line", (line) =>
{
  try
  {
    const message = JSON.parse(line);
    if (message.type === "started")
    {
      runId = message.runId;
      started();
    }
    else if (message.type === "native-event")
      writes = writes.then(() => appendFile(output, `${message.event}\n`));
    else if (message.type === "turn-completed" && !completed)
    {
      completed = true;
      completion(message);
    }
  }
  catch (failure)
  {
    error += `Invalid runner response: ${failure.message}\n`;
  }
});
child.stderr.on("data", (chunk) => { error += chunk; });

await Promise.race([ready, exited]);
if (!child.stdin.writable)
{
  await writeSupervisorRecord("failed", "The runner ended before accepting a turn.");
  throw new Error(`Source runner ended before it accepted a turn: ${error}`.trim());
}
child.stdin.write(`${JSON.stringify({type: "turn", prompt})}\n`);
const result = await Promise.race([terminal, exited.then(() => null)]);
await writes;
if (result)
  child.stdin.write(`${JSON.stringify({type: "complete"})}\n`);
const exit = await exited;
clearTimeout(deadline);
clearTimeout(forcedTermination);
clearInterval(keepAlive);
const exitCode = observedExitCode(exit);
if ((timedOut || exitCode !== 0) && runId)
  await recordFailedRun(exitCode, exit.signal);
if (!result || timedOut || exitCode !== 0)
{
  const reason = timedOut ? `The capture exceeded its ${timeoutSeconds}-second deadline.` :
    "The runner ended before the capture recorded a completed turn.";
  await writeSupervisorRecord("failed", reason, exitCode, exit.signal);
  throw new Error(`Source capture did not complete: ${error}`.trim());
}
await writeSupervisorRecord("completed", "The runner recorded a completed turn and exited normally.", exitCode, exit.signal);

function observedExitCode(exit)
{
  if (Number.isSafeInteger(exit.exitCode) && exit.exitCode >= 0)
    return exit.exitCode;
  const signalNumber = constants.signals[exit.signal];
  if (Number.isSafeInteger(signalNumber))
    return 128 + signalNumber;
  return 1;
}

async function recordFailedRun(exitCode, signal)
{
  const termination = signal ? `signal ${signal} (exit status ${exitCode})` : `exit status ${exitCode}`;
  const diagnostic = timedOut ?
    `Capture timed out after ${timeoutSeconds} seconds; the runner ended with ${termination}.` :
    `Runner ended with ${termination} before the capture completed.`;
  const maintenanceArguments = ["--fail-run", "--run-root", runRoot, "--run-id", runId, "--exit-code",
    String(exitCode), "--diagnostic", diagnostic];
  const maintenance = spawn(runner, maintenanceArguments, {stdio: ["ignore", "ignore", "pipe"]});
  let maintenanceError = "";
  maintenance.stderr.on("data", (chunk) => { maintenanceError += chunk; });
  const maintenanceExit = await new Promise((resolve) => maintenance.once("exit", resolve));
  if (maintenanceExit !== 0)
    throw new Error(`Unable to record failed source capture: ${maintenanceError}`.trim());
}

async function writeSupervisorRecord(state, reason, exitCode = null, signal = null)
{
  const record = {
    schemaVersion: 1,
    state,
    reason,
    processId: process.pid,
    runnerProcessId: child.pid,
    runId: runId ?? null,
    startedAt,
    finishedAt: state === "running" ? null : new Date().toISOString(),
    exitCode,
    signal
  };
  const temporaryRecord = `${supervisorRecord}.${process.pid}.tmp`;

  await writeFile(temporaryRecord, `${JSON.stringify(record, null, 2)}\n`);
  await rename(temporaryRecord, supervisorRecord);
}
