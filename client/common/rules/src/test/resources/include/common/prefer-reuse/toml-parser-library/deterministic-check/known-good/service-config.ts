import { parseTOML, type AST } from "toml-eslint-parser";

export function readServicePort(configuration: string): number
{
	const document = parseTOML(configuration);
	const serviceTable = document.body[0]?.body.find(
		(entry): entry is AST.TOMLTable => entry.type === "TOMLTable" && entry.resolvedKey[0] === "service");
	const port = serviceTable?.body.find((entry): entry is AST.TOMLKeyValue =>
		entry.type === "TOMLKeyValue" && entry.key.keys[0]?.name === "port");
	if (port?.value.kind !== "integer")
		throw new Error("The service port must be an integer.");
	return port.value.value;
}
