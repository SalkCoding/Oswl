"""Pure result checks for the opt-in local cluster rehearsal; no server or DB mutation."""
import argparse
from datetime import datetime
from html.parser import HTMLParser
from pathlib import Path
import re

class PageText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.text = []
    def handle_data(self, data):
        self.text.append(data.strip())

def session_identity(status, body, expected_email):
    page = PageText()
    page.feed(body)
    return (str(status) == "200" and expected_email in page.text
            and 'onboardingWizard(' in body and 'id="login-email"' not in body)

def session_rejected(status):
    # A server error or network failure is not evidence that logout invalidated a session.
    return str(status) in {"302", "401", "403"}

def scheduler_cycles(logs, period_seconds=20, minimum_cycles=2):
    cycles = {}
    marker = "[Monitor] Continuous monitoring cycle START"
    for owner, text in logs.items():
        for line in text.splitlines():
            if marker not in line:
                continue
            stamp = re.match(r"(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})", line)
            if not stamp:
                raise ValueError("Scheduler start has no recognizable timestamp")
            value = datetime.fromisoformat(stamp[1] + "T" + stamp[2])
            # Same-host local logs share a timezone. This does not test clock skew.
            bucket = int((value - datetime(1970, 1, 1)).total_seconds()) // period_seconds
            cycles.setdefault(bucket, []).append(owner)
    if len(cycles) < minimum_cycles:
        raise ValueError(f"Only {len(cycles)} observed cycles; need {minimum_cycles}")
    duplicate = {cycle: owners for cycle, owners in cycles.items() if len(owners) != 1}
    if duplicate:
        raise ValueError(f"Duplicate execution in a cron cycle: {duplicate}")
    return cycles

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    session = commands.add_parser("session")
    session.add_argument("status")
    session.add_argument("body", type=Path)
    session.add_argument("email")
    rejected = commands.add_parser("rejected")
    rejected.add_argument("status")
    scheduler = commands.add_parser("scheduler")
    scheduler.add_argument("log_a", type=Path)
    scheduler.add_argument("log_b", type=Path)
    args = parser.parse_args()
    if args.command == "session":
        if not session_identity(args.status, args.body.read_text(encoding="utf-8"), args.email):
            raise SystemExit("Expected authenticated onboarding identity; redirect/login/other user rejected")
        print("Authenticated onboarding identity matched")
    elif args.command == "rejected":
        if not session_rejected(args.status):
            raise SystemExit("Expected authentication redirect/denial, not a server or transport error")
        print("Protected request redirected or denied")
    else:
        try:
            cycles = scheduler_cycles({"A": args.log_a.read_text(encoding="utf-8"), "B": args.log_b.read_text(encoding="utf-8")})
        except ValueError as error:
            raise SystemExit(str(error))
        print(f"Exactly one execution in each of {len(cycles)} observed cron cycles; owners may change")

if __name__ == "__main__":
    main()
