"""Run generated CLI upload paths against loopback HTTP; installers never run."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]


class RetryIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="oswl-cli-retry-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.uploads = []
        self.status = 200
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                if self.path.endswith("/parse"):
                    value = {"components": [], "componentCount": 0}
                    status = 200
                else:
                    owner.uploads.append(json.loads(body))
                    status = owner.status
                    value = {"scanId": 17}
                    if status == 0:
                        self.close_connection = True
                        return
                result = json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(result)))
                self.end_headers()
                self.wfile.write(result)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.commands = {}
        bash = os.environ.get("OSWL_TEST_BASH") or shutil.which("bash")
        powershell = os.environ.get("OSWL_TEST_POWERSHELL") or shutil.which("pwsh")
        if not bash or not powershell:
            self.fail("Both Bash and PowerShell are required; configure OSWL_TEST_BASH/OSWL_TEST_POWERSHELL")
        zip_path = self.directory / "fixture.zip"
        bash_source = ROOT / "src/main/resources/static/scripts/install.sh"
        self.assertNotIn(b"\r", bash_source.read_bytes(), "Packaged Bash installer needs LF line endings")
        sh = bash_source.read_text(encoding="utf-8")
        sh = sh.split("<< 'SCRIPT_EOF'\n", 1)[1].split("\nSCRIPT_EOF", 1)[0]
        sh = sh.replace("# ── Entry point", 'load_config() { :; }\npack_manifests() { touch "$OSWL_TEST_ZIP"; printf %s "$OSWL_TEST_ZIP"; }\n# ── Entry point', 1)
        script = self.directory / "oswl.sh"
        script.write_text(sh, encoding="utf-8")
        self.commands["bash"] = [bash, str(script)]
        ps = (ROOT / "src/main/resources/static/scripts/install.ps1").read_text(encoding="utf-8")
        ps = ps.split("$MainScript = @'\n", 1)[1].split("\n'@", 1)[0]
        ps = ps.replace("# ── Entry point", """function Load-OswlConfig { $script:SavedApiKey = ''; $script:SavedServerUrl = '' }
function Pack-ManifestArchive { [IO.File]::WriteAllText($env:OSWL_TEST_ZIP, 'fixture'); return $env:OSWL_TEST_ZIP }
function Invoke-ParseManifests { return [pscustomobject]@{componentCount=0;components=@()} }
# ── Entry point""", 1)
        script = self.directory / "oswl.ps1"
        script.write_text(ps, encoding="utf-8-sig")
        self.commands["powershell"] = [powershell, "-NoProfile", "-File", str(script)]
        self.env = dict(os.environ, OSWL_TEST_ZIP=zip_path.as_posix())
        self.env["OSWL_VERSION"] = "unknown"

    def invoke(self, runtime, key=None):
        args = self.commands[runtime] + ["scan", str(self.directory), "-k", "fixture-api", "-u", "fixture@example.test", "-p", "fixture-password", "--server", self.url]
        environment = dict(self.env)
        if key is not None:
            if runtime == "bash":
                # Preserve literal newlines/empty arguments across the Windows native argv boundary.
                environment["OSWL_TEST_RETRY_KEY"] = key
                args = [args[0], "-c", 'exec "$BASH" "$@" --idempotency-key "$OSWL_TEST_RETRY_KEY"', "oswl-test"] + args[1:]
            else:
                args += ["--idempotency-key", key]
        return subprocess.run(args, env=environment, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)

    def test_new_invocations_get_distinct_keys(self):
        for runtime in self.commands:
            with self.subTest(runtime=runtime):
                first = self.invoke(runtime)
                self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
                second = self.invoke(runtime)
                self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
                a, b = self.uploads[-2:]
                self.assertRegex(a["idempotencyKey"], r"^[a-f0-9]{32}$")
                self.assertNotEqual(a["idempotencyKey"], b["idempotencyKey"])
                self.assertNotIn("fixture-password", first.stdout + first.stderr)

    def test_lost_response_can_be_retried_with_same_key(self):
        for runtime in self.commands:
            with self.subTest(runtime=runtime):
                self.status = 0
                count = len(self.uploads)
                lost = self.invoke(runtime)
                self.assertNotEqual(lost.returncode, 0)
                self.assertEqual(len(self.uploads), count + 1, "No implicit upload retry")
                original = self.uploads[-1]
                key = original["idempotencyKey"]
                self.assertIn(key, lost.stdout)
                self.status = 200
                retried = self.invoke(runtime, key)
                self.assertEqual(retried.returncode, 0, retried.stdout + retried.stderr)
                self.assertEqual(original, self.uploads[-1])

    def test_invalid_key_never_uploads_and_conflict_is_terminal(self):
        for runtime in self.commands:
            with self.subTest(runtime=runtime):
                count = len(self.uploads)
                for invalid in ("invalid key", "", "a" * 129, "key\n", "키"):
                    rejected = self.invoke(runtime, invalid)
                    self.assertNotEqual(rejected.returncode, 0, repr(invalid) + rejected.stdout + rejected.stderr)
                    self.assertEqual(len(self.uploads), count)
                self.status = 409
                conflict = self.invoke(runtime, "existing-key")
                self.assertNotEqual(conflict.returncode, 0)
                self.assertEqual(len(self.uploads), count + 1)
                self.assertIn("409", conflict.stdout + conflict.stderr)


if __name__ == "__main__":
    unittest.main()
