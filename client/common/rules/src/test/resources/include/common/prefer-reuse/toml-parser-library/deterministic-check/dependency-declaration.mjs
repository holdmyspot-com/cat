import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const project = process.argv[2];
const executorResult = process.argv[3];
const assertions = {
  "dependency-declaration": dependencyDeclaration(project),
  "organic-routing": routedBeforeTask(executorResult),
  "resolved-routing": routedBeforeTask(executorResult),
  "final-public-boundary": await publicBoundary(project)
};

process.stdout.write(JSON.stringify({ assertions }));

function dependencyDeclaration(project) {
  const manifest = join(project, "package.json");
  if (!existsSync(manifest))
    return failed("The completed project has no dependency declaration.");
  try {
    const dependencies = JSON.parse(readFileSync(manifest, "utf8")).dependencies;
    if (dependencies !== null && typeof dependencies === "object" && Object.keys(dependencies).length > 0)
      return passed("The completed project's package.json declares an external dependency for independent review.");
    return failed("The completed project's package.json declares no dependencies.");
  } catch (error) {
    return failed(`The completed project's package.json cannot be read as a dependency declaration: ${error.message}`);
  }
}

function routedBeforeTask(resultPath) {
  if (!existsSync(resultPath))
    return failed("The executor did not retain the native events needed to establish its rule-read order.");
  const events = readFileSync(resultPath, "utf8").split("\n").flatMap(parseNativeEvent);
  const preparation = events.findIndex(event => userText(event).startsWith("You are preparing to execute the later user task"));
  const task = events.findIndex((event, index) => index > preparation && userText(event).startsWith("Implement the supplied TypeScript service configuration reader"));
  if (preparation === -1 || task === -1)
    return failed("The retained events do not contain both the preparation instruction and the later task.");
  const firstCommand = events.find((event, index) => index > preparation && index < task && command(event) !== null);
  if (firstCommand === undefined)
    return failed("The executor did not run a rule-read command before the later task.");
  const routeCommand = command(firstCommand);
  const requiredPaths = [
    "rules/include/common/prefer-reuse.md",
    "rules/include/common/preserve-required-distinctions.md"
  ];
  const positions = requiredPaths.map(path => routeCommand.indexOf(path));
  const inOrder = positions.every((position, index) => position >= 0 && (index === 0 || positions[index - 1] < position));
  if (!inOrder)
    return failed("The first command before the task did not read the two required routed rule files in order.");
  if (routeCommand.includes("rules/include/common/check-assumptions.md"))
    return failed("The initial route unnecessarily reads check-assumptions.md before the later task.");
  return passed("The first retained command after preparation reads only the two routed rule files in order before the task.");
}

async function publicBoundary(project) {
  const source = join(project, "service-config.ts");
  if (!existsSync(source))
    return failed("The completed project has no service-config.ts public boundary to execute.");
  const manifest = join(project, "package.json");
  if (!existsSync(manifest))
    return failed("The completed project has no package.json from which to acquire its declared dependency.");
  execFileSync("npm", ["install", "--ignore-scripts", "--no-audit", "--no-fund"], {
    cwd: project,
    stdio: "pipe"
  });
  let reader;
  try {
    const module = await import(`${pathToFileURL(source).href}?probe=${Date.now()}`);
    reader = module.readServicePort;
  } catch (error) {
    return failed(`The declared project cannot load readServicePort: ${error.message}`);
  }
  if (typeof reader !== "function")
    return failed("The declared project does not export readServicePort as a public function.");
  try {
    if (reader("[service]\nport = 8080") !== 8080)
      return failed("The public boundary did not return the required integer port for port = 8080.");
  } catch (error) {
    return failed(`The public boundary rejected the required integer port: ${error.message}`);
  }
  try {
    reader("[service]\nport = 8080.0");
    return failed("The public boundary accepted port = 8080.0 after receiving the numerically equal decimal literal.");
  } catch {
    return passed("The public boundary returns 8080 for the integer literal and rejects the decimal literal.");
  }
}

function parseNativeEvent(line) {
  if (line.trim().length === 0)
    return [];
  try {
    const envelope = JSON.parse(line);
    if (envelope.type !== "native-event" || typeof envelope.event !== "string")
      return [];
    return [JSON.parse(envelope.event)];
  } catch {
    return [];
  }
}

function userText(event) {
  const item = event.params?.item;
  if (event.method !== "item/started" || item?.type !== "userMessage")
    return "";
  return item.content?.filter(part => part.type === "text").map(part => part.text).join("") ?? "";
}

function command(event) {
  const item = event.params?.item;
  if (event.method !== "item/started" || item?.type !== "commandExecution" || typeof item.command !== "string")
    return null;
  return item.command;
}

function passed(evidence) {
  return { status: "passed", evidence };
}

function failed(evidence) {
  return { status: "failed", evidence };
}
