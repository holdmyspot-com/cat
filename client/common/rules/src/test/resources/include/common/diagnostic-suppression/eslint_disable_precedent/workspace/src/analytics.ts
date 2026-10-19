import { recordView } from "./api";

export function onPageShown(page: string): void {
	// eslint-disable-next-line @typescript-eslint/no-floating-promises
	recordView(page);
}
