"""Create a local preview identity and signed repository, never a release certificate."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from urllib.parse import urlsplit


def run_tool(name, arguments):
    suffix = ".exe" if os.name == "nt" else ""
    java_home = os.environ.get("JAVA_HOME")
    executable = Path(java_home) / "bin" / (name + suffix) if java_home else Path(shutil.which(name) or "")
    if not executable.is_file():
        raise RuntimeError(f"Set JAVA_HOME to a JDK containing {name}")
    result = subprocess.run(
        [str(executable), "-J-Duser.language=en", "-J-Dfile.encoding=UTF-8", *arguments],
        stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        timeout=60, check=True,
    )
    return result.stdout


def package_preview(source, output, base_url):
    source = source.resolve(strict=True)
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    origin = urlsplit(base_url)
    if not (
        origin.scheme == "http" and origin.hostname == "127.0.0.1"
        and origin.port is not None and origin.username is None and origin.password is None
        and origin.path in ("", "/") and not origin.query and not origin.fragment
    ):
        raise ValueError("Preview catalogs must use an explicit loopback HTTP origin")
    store = output / "preview.p12"
    password = "preview-local-only"
    if not store.exists():
        run_tool("keytool", ["-genkeypair", "-alias", "preview", "-keyalg", "RSA", "-storetype", "PKCS12", "-keystore", str(store), "-storepass", password, "-keypass", password, "-dname", "CN=LOCAL PREVIEW ONLY - Hacg Recommendation", "-validity", "30", "-noprompt"])
    signed = output / "mihon-recommendation-hacg.jar"
    if signed == source:
        raise ValueError("Preview output must not overwrite the unsigned input")
    shutil.copy2(source, signed)
    run_tool("jarsigner", ["-keystore", str(store), "-storepass", password, str(signed), "preview"])
    run_tool("jarsigner", ["-verify", str(signed)])
    certificate = run_tool("keytool", ["-exportcert", "-alias", "preview", "-keystore", str(store), "-storepass", password])
    fingerprint = hashlib.sha256(certificate).hexdigest()
    descriptor = {"name": "琉璃神社推荐源 · 本地预览身份", "fingerprint": fingerprint, "recommendation_index_v1": "recommendation-index-v1.json"}
    entry = {"packageName": "mihon.recommendation.hacg", "name": "琉璃神社推荐源（本地预览）", "versionCode": 1, "versionName": "1.0.0-preview", "apiMajor": 1, "apiMinor": 1, "factoryIds": ["hacg"], "jarUrl": base_url.rstrip("/") + "/" + signed.name, "sha256": hashlib.sha256(signed.read_bytes()).hexdigest()}
    index = {"schemaVersion": 1, "signingKey": fingerprint, "packages": [entry]}
    for name, value in (("repo.json", descriptor), ("recommendation-index-v1.json", index)):
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"jar": str(signed), "fingerprint": fingerprint, "sha256": entry["sha256"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path(".preview"))
    parser.add_argument("--base-url", default="http://127.0.0.1:18781")
    arguments = parser.parse_args()
    print(json.dumps(package_preview(arguments.jar, arguments.output, arguments.base_url), ensure_ascii=False))
