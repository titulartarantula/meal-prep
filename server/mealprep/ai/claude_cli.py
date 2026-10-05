import json, os, subprocess
from .base import AIError, parse_json


class ClaudeCli:
    def __init__(self, bin_path: str, model: str, timeout: int = 180):
        self.bin, self.model, self.timeout = bin_path, model, timeout

    def complete_json(self, prompt: str, images: list[str] | None = None):
        if images:
            listing = "\n".join(f"Page {i}: {p}" for i, p in enumerate(images, 1))
            prompt = f"{prompt}\n\nUse the Read tool to view each image file, in this order:\n{listing}"
        # Tools: Read only when there are page images to look at; none for text-only calls, so text
        # from a fetched page can't steer the model into reading local files (e.g. ~/meal-prep/.env).
        tools = ["--tools", "Read", "--allowedTools", "Read"] if images else ["--tools", ""]
        # "--" ends options: --tools/--allowedTools are variadic and would otherwise swallow the prompt
        cmd = [self.bin, "-p", "--output-format", "json", "--model", self.model, *tools, "--", prompt]
        cwd = os.path.dirname(images[0]) if images else None
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=self.timeout, cwd=cwd)
        except subprocess.TimeoutExpired as e:
            raise AIError("claude CLI timed out") from e
        if proc.returncode != 0:
            raise AIError(f"claude CLI exit {proc.returncode}: {(proc.stderr or proc.stdout)[:300]}")
        try:
            result = json.loads(proc.stdout)["result"]
        except (json.JSONDecodeError, KeyError, TypeError) as e:
            raise AIError(f"unexpected CLI output: {proc.stdout[:300]}") from e
        return parse_json(result)
