"""Run the synthetic regression against immutable local release tags; never check out over work."""
import io
import os
from pathlib import Path
import subprocess
import tarfile

root = Path(__file__).resolve().parent.parent
fixture = Path("debugbundle-java-core/src/test/java/com/debugbundle/sdk/RedirectedStderrTest.java")
dependency = """<dependency><groupId>org.jboss.stdio</groupId><artifactId>jboss-stdio</artifactId>
<version>1.1.0.Final</version><scope>test</scope></dependency>"""
for tag in ("v3.0.0", "v3.0.1", "v3.1.0"):
    target = root / ".tmp/redirected-stderr" / tag
    target.mkdir(parents=True, exist_ok=True)
    archive = subprocess.check_output(["git", "archive", tag], cwd=root)
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        for member in source.getmembers():
            if Path(member.name).is_absolute() or ".." in Path(member.name).parts or not (member.isfile() or member.isdir()):
                raise ValueError("Unsafe archive entry")
        source.extractall(target)
    (target / fixture).write_bytes((root / fixture).read_bytes())
    pom = target / "debugbundle-java-core/pom.xml"
    pom.write_text(pom.read_text().replace("<dependencies>", "<dependencies>" + dependency, 1))
    log = root / ".tmp/redirected-stderr" / (tag + ".log")
    with log.open("w") as output:
        result = subprocess.run(["make", "test-core-focused", "TEST_CLASS=RedirectedStderrTest"],
                                cwd=target, env={**os.environ, "PWD": str(target)},
                                stdout=output, stderr=subprocess.STDOUT)
    print(f"{tag}: exit={result.returncode}, log={log.relative_to(root)}", flush=True)
