import { refreshSession } from "./api";

export function keepSessionAlive(): void {
	setInterval(() => {
		// eslint-disable-next-line @typescript-eslint/no-floating-promises
		refreshSession();
	}, 60_000);
}
