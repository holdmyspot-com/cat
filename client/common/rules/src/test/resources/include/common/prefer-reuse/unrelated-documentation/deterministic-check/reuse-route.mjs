import { existsSync, readFileSync } from "node:fs";

const executorResult = process.argv[2];
const assertions = {
  "unrelated-route-not-selected": noPreferReuseRead(executorResult)
};

process.stdout.write(JSON.stringify({ assertions }));

function noPreferReuseRead(resultPath) {
  if (!existsSync(resultPath))
    return failed("The executor did not retain the native command events needed to check its selected route.");
  const routeRead = readFileSync(resultPath, "utf8").split("\n").flatMap(parseNativeEvent).
    map(command).find(value => value !== null && value.includes("rules/include/common/prefer-reuse.md"));
  if (routeRead !== undefined)
    return failed("A retained native command action read Prefer Reuse for a documentation-only task.");
  return passed("No retained native command action reads Prefer Reuse; injected rule text is not a command action.");
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
