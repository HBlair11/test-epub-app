from pathlib import Path
import re
import sys


FILE = Path("app/build.gradle.kts")


def main():
    if not FILE.exists():
        print(f"ERROR: {FILE} was not found.")
        print("Run this script from the root of the Android project.")
        sys.exit(1)

    text = FILE.read_text(encoding="utf-8")

    code_match = re.search(
        r"versionCode\s*=\s*(\d+)",
        text,
    )

    name_match = re.search(
        r'versionName\s*=\s*"(\d+)\.(\d+)"',
        text,
    )

    if not code_match:
        print("ERROR: Could not find versionCode in app/build.gradle.kts.")
        sys.exit(1)

    if not name_match:
        print("ERROR: Could not find versionName in app/build.gradle.kts.")
        print('Expected format: versionName = "1.23"')
        sys.exit(1)

    old_code = int(code_match.group(1))
    old_major = int(name_match.group(1))
    old_minor = int(name_match.group(2))

    new_code = old_code + 1
    new_minor = old_minor + 1
    new_name = f"{old_major}.{new_minor}"

    new_text = re.sub(
        r"versionCode\s*=\s*\d+",
        f"versionCode = {new_code}",
        text,
        count=1,
    )

    new_text = re.sub(
        r'versionName\s*=\s*"[^"]+"',
        f'versionName = "{new_name}"',
        new_text,
        count=1,
    )

    FILE.write_text(new_text, encoding="utf-8")

    print()
    print("App version updated successfully.")
    print()
    print(f"versionCode: {old_code} -> {new_code}")
    print(f"versionName: {old_major}.{old_minor} -> {new_name}")
    print()
    print("Next:")
    print("  git add app/build.gradle.kts")
    print(f'  git commit -m "Bump app version to {new_name}"')
    print("  git push")
    print()


if __name__ == "__main__":
    main()
