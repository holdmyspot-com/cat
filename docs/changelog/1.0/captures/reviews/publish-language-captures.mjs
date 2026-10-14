// Publishes only completed, contract-conforming language review captures from an owned matrix run.
import {cp, mkdir, readFile, readdir, writeFile} from "node:fs/promises";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";

const reviewDirectory = dirname(fileURLToPath(import.meta.url));

const [matrixRoot, destination] = process.argv.slice(2);
if ([matrixRoot, destination].some((value) => !value?.trim()))
  throw new Error("Usage: publish-language-captures.mjs <matrix-root> <destination>");

const languages = ["typescript", "python", "java", "csharp", "cpp", "php", "go"];
const concerns = ["architecture", "business", "deployment", "design", "legal", "performance", "requirements",
  "security", "testing", "ux", "plan-review"];

async function filesBelow(directory, name)
{
  const files = [];
  for (const entry of await readdir(directory, {withFileTypes: true}))
  {
    const path = join(directory, entry.name);
    if (entry.isDirectory())
      files.push(...await filesBelow(path, name));
    else if (entry.name === name)
      files.push(path);
  }
  return files;
}

async function completedRun(directory)
{
  const manifests = await filesBelow(directory, "run.json");
  if (manifests.length !== 1)
    throw new Error(`${directory} must contain exactly one run manifest`);
  const manifest = JSON.parse(await readFile(manifests[0], "utf8"));
  if (manifest.state !== "completed")
    throw new Error(`${manifests[0]} is ${manifest.state}, not completed`);
  return manifests[0];
}

async function sourceDirectory(root, language, harness, mode, concern)
{
  const directory = join(root, language, "runs", `${harness}-${mode}-${concern}`);
  await completedRun(directory);
  return directory;
}

async function publish(root, language, harness, mode, concern, outputName)
{
  const source = await sourceDirectory(root, language, harness, mode, concern);
  const events = join(root, language, "outputs", `${harness}-${mode}-${concern}.events.jsonl`);
  const target = join(destination, language);
  await mkdir(target, {recursive: true});
  await cp(events, join(target, `${outputName}.events.jsonl`));
  const request = join(source, "request.txt");
  await readFile(request);
  await cp(request, join(target, `${outputName}.request.md`));
  await cp(await completedRun(source), join(target, `${outputName}.run.json`));
  if (harness === "codex" && mode === "cat")
  {
    const validation = `${events}.validation.json`;
    const result = JSON.parse(await readFile(validation, "utf8"));
    if (result.accepted !== true)
      throw new Error(`${validation} was not accepted`);
    await cp(validation, join(target, `${outputName}.validation.json`));
  }
}

for (const language of languages)
  for (const concern of concerns)
    for (const harness of ["codex", "claude"])
      for (const mode of ["base", "cat"])
        await publish(matrixRoot, language, harness, mode, concern, `${harness}-${mode}-${concern}`);

await writeFile(join(destination, "published-from.json"), `${JSON.stringify({matrixRoot}, null, 2)}\n`);
