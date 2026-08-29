#!/usr/bin/env node
// Retains one or two delegated reviewer turns and accepts the final fresh CAT response only when its injected contract passes.
import {appendFile, readFile, writeFile} from "node:fs/promises";
import {spawn} from "node:child_process";
import {createInterface} from "node:readline";
import {delegationPrompt, reviewFormat} from "./review-format.mjs";

const [runner, artifact, project, promptFile, output, runRoot, reviewer] = process.argv.slice(2);
if ([runner, artifact, project, promptFile, output, runRoot, reviewer].some((value) => !value))
	throw new Error("Usage: capture-codex-review.mjs <runner> <artifact> <project> <prompt-file> <output> <run-root> <reviewer>");
const validationOutput = `${output}.validation.json`;

function latestReviewerResponse(events)
{
  const thread = events.filter((event) => event.method === "item/completed").map((event) => event.params?.item).
    filter((item) => item?.type === "subAgentActivity" && item.kind === "started").at(-1)?.agentThreadId;
  return events.filter((event) => event.method === "item/completed" && event.params?.threadId === thread).
    map((event) => event.params.item).filter((item) => item?.type === "agentMessage" &&
      item.phase === "final_answer").at(-1)?.text;
}

function valid(response)
{
  if (response?.trim() === "APPROVED")
    return true;
  if (response?.split("\n").find((line) => line.trim()) !== "## Findings")
    return false;
  const lines = response.split("\n");
  const topLevelFindings = lines.filter((line) => /^- \*\*/.test(line));
  const starts = lines.map((line, index) => ({line, index})).filter(({line}) =>
    /^- \*\*\[[^\]]+\]\([^\s)]+\)/.test(line));
  if (starts.length === 0 || starts.length !== topLevelFindings.length)
    return false;
  if (/\[[^\]]+\.(?:java|md|yaml)(?::\d+(?:-\d+)?)?\](?!\()|\[[^\]]+\.(?:java|md|yaml)(?::\d+(?:-\d+)?)?\]\(\s/m.test(response))
    return false;
  return starts.every(({index}, findingIndex) =>
  {
    const next = starts[findingIndex + 1]?.index ?? lines.length;
    const finding = lines.slice(index, next).join("\n");
    return ["Evidence", "Consequence", "Correction"].every((field) =>
      new RegExp(`^  - \\*\\*${field}:\\*\\*`, "m").test(finding));
  });
}

const initialPrompt = await readFile(promptFile, "utf8");
const reviewContract = await reviewFormat(artifact);
await writeFile(output, "");
const runnerArguments = ["--artifact", artifact, "--project", project, "--run-root", runRoot,
	"--keep-run", "--install-cat"];
const child = spawn(runner, runnerArguments, {stdio: ["pipe", "pipe", "pipe"], env: process.env});
// A pending Promise does not keep a non-interactive Node process alive. Retain this parent until it records the child
// runner's final raw events; otherwise the runner becomes orphaned and the capture has no accepted terminal response.
const keepAlive = setInterval(() => {}, 1_000);
const events = [];
const turns = [];
let writes = Promise.resolve();
let error = "";
let started;
const startedPromise = new Promise((resolve) => { started = resolve; });
let completed;
const lines = createInterface({input: child.stdout});
lines.on("line", (line) =>
{
  try
  {
    const message = JSON.parse(line);
    if (message.type === "started")
      started();
    else if (message.type === "native-event")
    {
      events.push(JSON.parse(message.event));
      writes = writes.then(() => appendFile(output, `${message.event}\n`));
    }
    else if (message.type === "turn-completed")
    {
      if (completed)
      {
        completed(message);
        completed = null;
      }
      else
        turns.push(message);
    }
  }
  catch (failure)
  {
    error += `Invalid runner response: ${failure.message}\n`;
  }
});
child.stderr.on("data", (chunk) => { error += chunk; });

function send(prompt)
{
  child.stdin.write(`${JSON.stringify({type: "turn", prompt})}\n`);
  if (turns.length)
    return Promise.resolve(turns.shift());
  return new Promise((resolve) => { completed = resolve; });
}

await startedPromise;
await send(delegationPrompt(initialPrompt, reviewContract));
await writes;
let corrections = 0;
if (!valid(latestReviewerResponse(events)))
{
  corrections++;
	const correction = "The reviewer response you just relayed did not meet its required format. Delegate a fresh review to " +
		"the same installed CAT reviewer, include this complete Review Contract in the child task, and return that response " +
		"without rewriting it.";
	await send(delegationPrompt(correction, reviewContract));
  await writes;
}
const accepted = valid(latestReviewerResponse(events));
await writeFile(validationOutput, `${JSON.stringify({accepted, corrections})}\n`);
child.stdin.write(`${JSON.stringify({type: "complete"})}\n`);
const code = await new Promise((resolve) => child.once("exit", resolve));
clearInterval(keepAlive);
if (code !== 0 || !accepted)
  throw new Error(`Codex reviewer capture did not satisfy its injected contract. ${error}`.trim());
