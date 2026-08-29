#!/usr/bin/env node
// Runs an independent model review of a generated TypeScript monetary capture and retains its raw verdict.
import {copyFile, mkdir, readFile, writeFile} from "node:fs/promises";
import {dirname, join} from "node:path";
import {spawn} from "node:child_process";

const [runner, artifact, project, taskFile, output, runRoot] = process.argv.slice(2);
if ([runner, artifact, project, taskFile, output, runRoot].some((value) => !value?.trim()))
  throw new Error("Usage: validate-money-capture.mjs <runner> <artifact> <project> <task-file> <events-output> <run-root>");

const task = await readFile(taskFile, "utf8");
const prompt = `
Review the generated TypeScript checkout-price calculator in this project. Do not edit any file.

The following is the complete generation task. It is the sole caller-visible contract for this review:

--- task begins ---
${task}--- task ends ---

Accept it only when every task requirement is met and it uses a declared established exact-decimal or money dependency
rather than binary floating point or custom decimal, rational, or bigint monetary arithmetic. Inspect the source and any
package manifest or lockfile needed to support the conclusion. Do not require or reject an API shape, parameter or
import name, representation, validation policy, or behavior that the generation task does not require. Do not add
caller-visible requirements after generation. Your complete final response must be exactly APPROVED, or exactly
REJECTED: followed by one concise, evidence-based reason.
`;
const promptFile = `${output}.prompt.txt`;
await writeFile(promptFile, prompt);

const evidenceProject = join(runRoot, "project");
await mkdir(evidenceProject, {recursive: true});
await copyEvidence(project, evidenceProject);
const captureSource = join(dirname(new URL(import.meta.url).pathname), "capture-source.mjs");
await run(process.execPath, [captureSource, runner, artifact, evidenceProject, promptFile, output, runRoot, "base"]);

const response = latestResponse((await readFile(output, "utf8")).split("\n").filter(Boolean).map(JSON.parse));
if (response?.trim() !== "APPROVED")
  throw new Error(`TypeScript monetary model validation rejected the capture: ${response?.trim() ?? "no final response"}`);

function latestResponse(events)
{
  const claudeResponse = events.filter((event) => event.type === "result").at(-1)?.result;
  if (claudeResponse)
    return claudeResponse;

  return events.filter((event) => event.method === "item/completed").map((event) => event.params?.item).
    filter((item) => item?.type === "agentMessage" && item.phase === "final_answer").at(-1)?.text;
}

async function copyEvidence(sourceDirectory, destinationDirectory)
{
  for (const filename of ["checkout-price-calculator.ts", "package.json", "package-lock.json", "npm-shrinkwrap.json",
    "pnpm-lock.yaml", "yarn.lock"])
  {
    try
    {
      await copyFile(join(sourceDirectory, filename), join(destinationDirectory, filename));
    }
    catch (error)
    {
      if (error.code !== "ENOENT")
        throw error;
    }
  }
}

function run(command, arguments_)
{
  return new Promise((resolve, reject) =>
  {
    const child = spawn(command, arguments_, {stdio: "inherit"});
    child.once("error", reject);
    child.once("exit", (code) =>
    {
      if (code === 0)
        resolve();
      else
        reject(new Error(`Model validation runner exited with code ${code}`));
    });
  });
}
