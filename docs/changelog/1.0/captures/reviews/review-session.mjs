import {readFile, stat, writeFile} from "node:fs/promises";
import {join} from "node:path";

const captureName = "capture.json";
const manifestName = "session.json";
const manifestVersion = 6;

/**
 * Finds the results copy for the session reported by the launcher and requires a successful manifest and project.
 */
export async function completedSession(results, harness, sessionId)
{
  if (typeof sessionId !== "string" || !/^[A-Za-z0-9_-][A-Za-z0-9._-]*$/.test(sessionId))
    throw new Error(`Invalid ${harness} session ID: ${JSON.stringify(sessionId)}`);
  if (harness !== "codex" && harness !== "claude")
    throw new Error(`Unknown review harness: ${harness}`);

  const session = join(results, harness, sessionId);
  const path = join(session, manifestName);
  const manifest = JSON.parse(await readFile(path, "utf8"));
  if (manifest.schema_version !== manifestVersion || manifest.id !== sessionId || manifest.harness !== harness)
    throw new Error(`${path} does not identify the observed ${harness} session ${sessionId}`);
  if (manifest.state !== "completed" || manifest.exit_code !== 0 ||
    !Number.isSafeInteger(manifest.turn_number) || manifest.turn_number < 1)
    throw new Error(`${path} does not record a successful session. ${manifest.diagnostic ?? ""}`.trim());
  if (!(await stat(join(session, "project"))).isDirectory())
    throw new Error(`${path} has no retained project folder`);
  return path;
}

/**
 * Records the successful session identity and its requested profile for the publisher.
 */
export async function recordCapture(results, harness, sessionId, model, effort)
{
  await completedSession(results, harness, sessionId);
  const capture = {schema_version: 1, session_id: sessionId, model, effort};
  await writeFile(join(results, captureName), `${JSON.stringify(capture)}\n`, {flag: "wx"});
}

/**
 * Returns the manifest path and requested model and effort of the captured session.
 */
export async function readCapture(results, harness)
{
  const path = join(results, captureName);
  const capture = JSON.parse(await readFile(path, "utf8"));
  if (capture.schema_version !== 1 || typeof capture.model !== "string" || !capture.model.trim() ||
    typeof capture.effort !== "string" || !capture.effort.trim())
    throw new Error(`${path} has no supported capture profile`);
  const manifest = await completedSession(results, harness, capture.session_id);
  return {manifest, model: capture.model, effort: capture.effort};
}
