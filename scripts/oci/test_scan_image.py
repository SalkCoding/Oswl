import hashlib
import importlib.util
import io
import json
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
from pathlib import Path

spec = importlib.util.spec_from_file_location("scan_image", Path(__file__).with_name("scan-image.py"))
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


class ImageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.blobs = self.root / "layout" / "blobs" / "sha256"
        self.blobs.mkdir(parents=True)
        self.work = self.root / "work"; self.work.mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def blob(self, data, media="application/vnd.oci.image.layer.v1.tar"):
        value = hashlib.sha256(data).hexdigest()
        (self.blobs / value).write_bytes(data)
        return dict(digest="sha256:"+value, size=len(data), mediaType=media)

    def layer(self, entries):
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode="w") as tar:
            for name, content in entries:
                entry = tarfile.TarInfo(name); raw=content.encode();entry.size=len(raw)
                tar.addfile(entry,io.BytesIO(raw))
        return self.blob(output.getvalue())

    def image(self, layers):
        config=self.blob(json.dumps(dict(os="linux", architecture="amd64", rootfs=dict(type="layers",diff_ids=[layer["digest"] for layer in layers]))).encode(),"application/vnd.oci.image.config.v1+json")
        return self.blob(json.dumps(dict(schemaVersion=2,config=config,layers=layers)).encode(),"application/vnd.oci.image.manifest.v1+json")["digest"]

    def scan(self, reference, budget=None):
        return scanner.inspect(scanner.ImageSource(self.work,budget or scanner.Budget(),layout=self.root/"layout"),reference)

    def test_real_layer_inventory_uses_final_installed_database(self):
        base=self.layer([("etc/os-release",'ID=alpine\nVERSION_ID="3.16.0"\n'),("lib/apk/db/installed","P:old-binary\no:old-source\nV:1.0-r0\n")])
        # A whiteout after a same-layer replacement must only remove the lower layer.
        update=self.layer([("lib/apk/db/installed","P:new-binary\no:new-source\nV:2.0-r1\n"),("lib/apk/db/.wh..wh..opq","")])
        payload, report=self.scan(self.image([base,update]))
        self.assertEqual(payload["components"][0]["name"],"new-source")
        self.assertEqual(payload["components"][0]["version"],"2.0-r1")
        self.assertEqual(payload["components"][0]["ecosystem"],"ALPINE:V3.16")
        self.assertFalse(report["executedImage"])

    def test_deleted_database_is_incomplete_not_clean(self):
        base=self.layer([("etc/os-release","ID=alpine\nVERSION_ID=3.16\n"),("lib/apk/db/installed","P:pkg\nV:1\n")])
        removed=self.layer([("lib/apk/.wh.db","")])
        with self.assertRaisesRegex(ValueError,"database"):
            self.scan(self.image([base,removed]))

    def test_debian_uses_installed_source_version_and_ignores_removed_packages(self):
        state={"etc/os-release":b'ID=debian\nVERSION_ID="12"\n',"var/lib/dpkg/status":b'Package: binary\nStatus: install ok installed\nSource: original (1:2.0-1)\nVersion: 1:2.0-1+b1\n\nPackage: removed\nStatus: deinstall ok config-files\nVersion: 3\n'}
        result=scanner.inventory(state)
        self.assertEqual([(r["name"],r["version"]) for r in result],[("original","1:2.0-1")])

    def test_checksum_traversal_and_budget_fail_closed(self):
        layer=self.layer([("../outside","never written")])
        with self.assertRaisesRegex(ValueError,"Unsafe"):
            self.scan(self.image([layer]))
        with self.assertRaisesRegex(ValueError,"budget"):
            scanner.apply_layer(self.blobs/scanner.digest(layer["digest"]),layer["mediaType"],layer["digest"],{},scanner.Budget(expanded=100))
        safe=self.layer([("etc/os-release","ID=alpine")])
        with self.assertRaisesRegex(ValueError,"checksum"):
            scanner.apply_layer(self.blobs/scanner.digest(safe["digest"]),safe["mediaType"],"sha256:"+"0"*64,{},scanner.Budget())

    def test_registry_redirect_does_not_forward_bearer_token(self):
        registry=scanner.Registry("registry.example","library/test",scanner.Budget());registry.token="sensitive-token"
        calls=[]
        def open_request(request,timeout):
            calls.append(request)
            if len(calls)==1:
                raise urllib.error.HTTPError(request.full_url,302,"redirect",{"Location":"https://cdn.example/blob"},None)
            return io.BytesIO(b"blob")
        registry.opener.open=open_request
        registry.fetch("blobs","sha256:"+"a"*64,self.work/"download",100)
        self.assertEqual(calls[0].get_header("Authorization"),"Bearer sensitive-token")
        self.assertIsNone(calls[1].get_header("Authorization"))

    def test_credentials_are_not_sent_to_foreign_token_realm(self):
        registry=scanner.Registry("registry.example","library/test",scanner.Budget(),dict(username="user",password="secret"))
        with self.assertRaisesRegex(ValueError,"not trusted"):
            registry.authenticate('Bearer realm="https://attacker.example/token",service="registry.example"')

    def test_advisory_empty_success_differs_from_truncation(self):
        packages=[dict(name="openssl",version="1.1.1o-r0",ecosystem="ALPINE:V3.16")]
        with patch.object(scanner.urllib.request,"build_opener") as opener:
            opener.return_value.open.return_value=io.BytesIO(b'{"results":[{}]}')
            self.assertEqual(scanner.query_osv(packages,scanner.Budget())["packages"][0]["ids"],[])
            opener.return_value.open.return_value=io.BytesIO(b'{"results":[{"next_page_token":"more"}]}')
            with self.assertRaisesRegex(ValueError,"Incomplete advisory"):
                scanner.query_osv(packages,scanner.Budget())


if __name__ == "__main__":
    unittest.main()
