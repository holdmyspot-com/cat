#!/usr/bin/env node
// Captures one source-generation or validation turn through the installed CAT runner.
import {createReadStream} from "node:fs";
import {open, readFile, rename, writeFile} from "node:fs/promises";
import {isAbsolute, join, resolve} from "node:path";
import {spawn} from "node:child_process";
import {constants} from "node:os";
import {createInterface} from "node:readline";
import {fileURLToPath} from "node:url";

const recordsName = "capture-input.jsonl";
const transcriptName = "capture-transcript.jsonl";
const supervisorName = "capture-supervisor.json";
const manifestName = "session.json";
const runnerTerminationGraceMs = 15_000;

/**
 * Captures one prompt from the project using the requested installed harness and profile.
 * Writes runner and supervisor evidence under the caller-owned results folder and exports native events to output.
 * Returns the successful session ID and final response. A failed launcher, turn or copy rejects the capture.
 */
export async function captureSource(
  {runner, harness, project, promptFile, output, resultsFolder, mode, model, effort}, timeoutSeconds)
{
  const prompt = await readFile(promptFile, "utf8");
  const input = join(resultsFolder, recordsName);
  const transcript = join(resultsFolder, transcriptName);
  const supervisorRecord = join(resultsFolder, supervisorName);
  await writeFile(input, [JSON.stringify({type: "turn", prompt}), JSON.stringify({type: "complete"}), ""].join("\n"));

  const args = ["--project", project, "--results", resultsFolder, "--input", input, "--output", transcript,
    "--model", model, "--effort", effort];
  if (mode === "cat")
    args.push("--install-cat");

  const record = {
    schema_version: 3,
    state: "running",
    reason: "The supervisor accepted the capture and is awaiting a terminal runner result.",
    process_id: process.pid,
    runner_process_id: null,
    session_id: null,
    started_at: new Date().toISOString(),
    finished_at: null,
    exit_code: null,
    signal: null
  };
  await writeSupervisorRecord(supervisorRecord, record);

  let failure;
  let response;
  try
  {
    const exit = await runCapture(runner, args, timeoutSeconds, record);
    record.exit_code = exit.code;
    record.signal = exit.signal;
    try
    {
      response = await exportTranscript(transcript, output, record);
    }
    catch (error)
    {
      if (exit.code === 0 || error.code !== "ENOENT")
        throw error;
    }

    if (exit.interrupted)
    {
      record.state = "interrupted";
      throw new Error("The supervisor received SIGTERM before recording a terminal result.");
    }
    if (exit.timedOut)
      throw new Error(`Capture timed out after ${timeoutSeconds} seconds. Runner exit status was ${exit.code}.`);
    if (exit.code !== 0)
      throw new Error(`Source runner exited with status ${exit.code}.${exit.stderr ? "\n" + exit.stderr : ""}`);
    if (response?.type !== "turn_completed" || response.exit_code !== 0)
      throw new Error(`Source runner returned no successful completed turn.${response?.error ? "\n" + response.error : ""}`);

    await requireCompletedManifest(resultsFolder, harness, record.session_id);
    record.state = "completed";
    record.reason = "The runner recorded a completed turn and copied its session results.";
  }
  catch (error)
  {
    if (record.state === "running")
      record.state = "failed";
    record.reason = error.message;
    failure = error;
  }

  record.finished_at = new Date().toISOString();
  try
  {
    await writeSupervisorRecord(supervisorRecord, record);
  }
  catch (error)
  {
    if (failure)
      throw new Error(`${failure.message}\nUnable to record the failed capture. ${error.message}`, {cause: failure});
    throw error;
  }
  if (failure)
    throw failure;

  return {sessionId: record.session_id, assistantResponse: response.assistant_response};
}

/** Runs the installed launcher and waits for its pipes to close. On timeout or SIGTERM, it terminates the launcher. */
async function runCapture(runner, args, timeoutSeconds, record)
{
  const child = spawn(runner, args, {stdio: ["ignore", "ignore", "pipe"]});
  record.runner_process_id = child.pid ?? null;
  let stderr = "";
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => { stderr = (stderr + chunk).slice(-8192); });
  let timedOut = false;
  let interrupted = false;
  let forceTermination;

  const terminate = () =>
  {
    child.kill("SIGTERM");
    forceTermination ??= setTimeout(() => child.kill("SIGKILL"), runnerTerminationGraceMs);
  };
  const onTerminate = () =>
  {
    interrupted = true;
    terminate();
  };
  process.once("SIGTERM", onTerminate);
  const deadline = setTimeout(() =>
  {
    timedOut = true;
    terminate();
  }, timeoutSeconds * 1_000);

  try
  {
    const {exitCode, signal} = await new Promise((resolveExit, reject) =>
    {
      child.once("error", reject);
      child.once("close", (exitCode, signal) => resolveExit({exitCode, signal}));
    });
    const code = exitCode ?? (signal ? (128 + constants.signals[signal]) : 1);
    return {code, signal, stderr: stderr.trim(), timedOut, interrupted};
  }
  finally
  {
    process.removeListener("SIGTERM", onTerminate);
    clearTimeout(deadline);
    clearTimeout(forceTermination);
  }
}

/** Copies the runner transcript's native event strings in order and returns its one turn's terminal record. */
async function exportTranscript(transcript, output, record)
{
  const events = await open(output, "w");
  let response;
  try
  {
    const lines = createInterface({input: createReadStream(transcript, {encoding: "utf8"}), crlfDelay: Infinity});
    for await (const line of lines)
    {
      const message = JSON.parse(line);
      switch (message.type)
      {
        case "session_id":
          record.session_id = message.session_id;
          break;
        case "harness_event":
          if (typeof message.event !== "string")
            throw new Error(`Runner transcript ${transcript} contains a non-text native event.`);
          await events.appendFile(`${message.event}\n`);
          break;
        case "turn_completed":
        case "turn_failed":
          response = message;
          break;
      }
    }
  }
  finally
  {
    await events.close();
  }
  return response;
}

/** Confirms the copied manifest belongs to the emitted session ID and reports a successful completed turn. */
async function requireCompletedManifest(resultsFolder, harness, sessionId)
{
  if (typeof sessionId !== "string" || !/^[A-Za-z0-9_-][A-Za-z0-9._-]*$/.test(sessionId))
    throw new Error("Source runner did not report a session ID that can name its results copy.");

  const path = join(resultsFolder, harness, sessionId, manifestName);
  const manifest = JSON.parse(await readFile(path, "utf8"));
  if (manifest.id !== sessionId || manifest.harness !== harness)
    throw new Error(`Manifest identity does not match ${harness} session ${sessionId}: ${path}`);
  if (manifest.state !== "completed" || manifest.exit_code !== 0 ||
    !Number.isInteger(manifest.turn_number) || manifest.turn_number < 1)
    throw new Error(`The manifest for ${harness} session ${sessionId} records no successful completed turn.` +
      (manifest.diagnostic ? "\n" + manifest.diagnostic : ""));
}

/** Atomically records the current state and terminal reason for the capture supervisor. */
async function writeSupervisorRecord(path, record)
{
  const temporaryRecord = `${path}.${process.pid}.tmp`;
  await writeFile(temporaryRecord, `${JSON.stringify(record, null, 2)}\n`);
  await rename(temporaryRecord, path);
}

/** Resolves the shared supervisor deadline from the caller's optional positive integer override. */
export function captureTimeoutSeconds(value)
{
  const timeoutSeconds = Number(value ?? 600);
  if (!Number.isSafeInteger(timeoutSeconds) || timeoutSeconds < 1)
    throw new Error("CAPTURE_TIMEOUT_SECONDS must be a positive whole number.");
  return timeoutSeconds;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url))
{
  const [runner, harness, project, promptFile, output, resultsFolder, mode, model, effort] = process.argv.slice(2);
  if ([runner, project, promptFile, output, resultsFolder, model, effort].some((value) => !value?.trim()) ||
    !isAbsolute(runner) || !["codex", "claude"].includes(harness) || !["base", "cat"].includes(mode))
  {
    throw new Error("Usage: capture-source.mjs <absolute runner> <codex|claude> <project> <prompt-file> <events-output> " +
      "<results> <base|cat> <model> <effort>");
  }
  const result = await captureSource({runner, harness, project, promptFile, output, resultsFolder, mode, model, effort},
    captureTimeoutSeconds(process.env.CAPTURE_TIMEOUT_SECONDS));
  console.log(result.sessionId);
}
