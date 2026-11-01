"""Reject local Maven metadata and incomplete module layouts before upload."""

import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree


def validate_bundle(archive, version, artifacts):
    poms = set()
    with zipfile.ZipFile(archive) as bundle:
        for entry in bundle.infolist():
            if entry.is_dir():
                continue
            parts = entry.filename.split("/")
            if (len(parts) != 5 or parts[:2] != ["com", "debugbundle"]
                    or parts[2] not in artifacts or parts[3] != version
                    or not parts[4].startswith(parts[2] + "-" + version)
                    or parts[4].startswith("maven-metadata")
                    or parts[4] == "_remote.repositories"):
                raise ValueError("invalid_central_bundle_entry:" + entry.filename)
            if parts[4] == parts[2] + "-" + version + ".pom":
                poms.add(parts[2])
    if poms != set(artifacts):
        raise ValueError("central_bundle_missing_pom:" + ",".join(sorted(set(artifacts) - poms)))


def main():
    root = Path(__file__).resolve().parents[1]
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    project = ElementTree.parse(root / "pom.xml").getroot()
    version = project.findtext("m:version", namespaces=ns)
    artifacts = {project.findtext("m:artifactId", namespaces=ns)}
    for module in project.findall("m:modules/m:module", ns):
        child = ElementTree.parse(root / module.text / "pom.xml").getroot()
        artifacts.add(child.findtext("m:artifactId", namespaces=ns))
    archive = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "target/central-publishing/central-bundle.zip"
    validate_bundle(archive, version, artifacts)
    print(f"Central bundle verified: {len(artifacts)} complete {version} components; no local metadata")


if __name__ == "__main__":
    main()
