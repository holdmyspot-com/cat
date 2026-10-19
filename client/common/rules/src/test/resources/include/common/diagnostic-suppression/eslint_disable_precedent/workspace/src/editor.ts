import { {{save_function}} } from "./api";
import { reportError } from "./errors";

export class Editor {
	private text = "";

	onChange(text: string): void {
		this.text = text;
		{{save_function}}(this.text);
	}
}
