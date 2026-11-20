// Publishes matched Base and CAT reviews from completed sessions in an owned matrix workspace.
import {cp, mkdir, readFile, stat} from "node:fs/promises";
import {join} from "node:path";
import {readCapture} from "./review-session.mjs";

const [matrixRoot, destination] = process.argv.slice(2);
if ([matrixRoot, destination].some((value) => !value?.trim()))
  throw new Error("Usage: publish-language-captures.mjs <matrix-root> <destination>");

const languages = ["typescript", "python", "java", "csharp", "cpp", "php", "go"];
const concerns = ["architecture", "business", "deployment", "design", "legal", "performance", "requirements",
  "security", "testing", "ux", "plan-review"];
const publishedManifestSuffix = ".session.json";

/**
 * Verifies one cell's manifest identity, retained task, native events, and Codex validation when required.
 */
async function publicationFiles(language, harness, mode, concern)
{
  const name = `${harness}-${mode}-${concern}`;
  const results = join(matrixRoot, language, "sessions", name);
  const capture = await readCapture(results, harness);
  const requestPath = join(results, "request.txt");
  const request = await readFile(requestPath, "utf8");
  const events = join(matrixRoot, language, "outputs", `${name}.events.jsonl`);
  const eventFile = await stat(events);
  if (!eventFile.isFile() || eventFile.size === 0)
    throw new Error(`${events} has no native events to publish`);

  const files = [[events, `${name}.events.jsonl`], [requestPath, `${name}.request.md`],
    [capture.manifest, `${name}${publishedManifestSuffix}`]];
  if (harness === "codex" && mode === "cat")
  {
    const validation = `${events}.validation.json`;
    const result = JSON.parse(await readFile(validation, "utf8"));
    if (result.accepted !== true)
      throw new Error(`${validation} does not record an accepted review`);
    files.push([validation, `${name}.validation.json`]);
  }
  return {...capture, request, files};
}

const publication = [];
for (const language of languages)
  for (const concern of concerns)
    for (const harness of ["codex", "claude"])
    {
      const base = await publicationFiles(language, harness, "base", concern);
      const cat = await publicationFiles(language, harness, "cat", concern);
      if (!base.request.trim() || base.request !== cat.request)
        throw new Error(`${language} ${harness} ${concern} requires the same nonempty Base and CAT review task`);
      if (base.model !== cat.model || base.effort !== cat.effort)
        throw new Error(`${language} ${harness} ${concern} used different model profiles for Base and CAT`);
      publication.push({language, files: [...base.files, ...cat.files]});
    }

for (const {language, files} of publication)
{
  const target = join(destination, language);
  await mkdir(target, {recursive: true});
  for (const [source, name] of files)
    await cp(source, join(target, name));
}
