import { getStaticTOMLValue, parseTOML } from "toml-eslint-parser";

export function readServicePort(configuration: string): number
{
	const value = getStaticTOMLValue(parseTOML(configuration)) as {service?: {port?: unknown}};
	if (typeof value.service?.port !== "number" || !Number.isInteger(value.service.port))
		throw new Error("The service port must be an integer.");
	return value.service.port;
}
