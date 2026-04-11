import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[2]
BACKEND_DIR = ROOT / "backend"


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class BackendServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp_dir = tempfile.TemporaryDirectory()
        cls.port = free_port()
        cls.admin_token = "test-admin-token"
        cls.device_token = "test-device-token"
        env = os.environ.copy()
        env.update(
            {
                "HOST": "127.0.0.1",
                "PORT": str(cls.port),
                "ADMIN_TOKEN": cls.admin_token,
                "DEVICE_API_TOKEN": cls.device_token,
                "DB_PATH": str(Path(cls.temp_dir.name) / "tracker.db"),
                "FCM_SERVICE_ACCOUNT_JSON": str(Path(cls.temp_dir.name) / "missing-service-account.json"),
            }
        )
        cls.process = subprocess.Popen(
            [sys.executable, "server.py"],
            cwd=BACKEND_DIR,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        cls.base_url = f"http://127.0.0.1:{cls.port}"
        deadline = time.time() + 15
        while time.time() < deadline:
            try:
                cls.request_json("/api/health")
                break
            except Exception:
                time.sleep(0.2)
        else:
            stdout, stderr = cls.process.communicate(timeout=2)
            raise RuntimeError(f"backend failed to start\nstdout:\n{stdout}\nstderr:\n{stderr}")

    @classmethod
    def tearDownClass(cls):
        cls.process.terminate()
        try:
            cls.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            cls.process.kill()
            cls.process.wait(timeout=5)
        cls.temp_dir.cleanup()

    @classmethod
    def request_json(cls, path, method="GET", payload=None, headers=None):
        request_headers = {"Content-Type": "application/json"}
        if headers:
            request_headers.update(headers)
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = Request(f"{cls.base_url}{path}", data=data, headers=request_headers, method=method)
        with urlopen(request, timeout=10) as response:
            body = response.read().decode("utf-8")
            return response.status, json.loads(body) if body else {}

    @classmethod
    def request_text(cls, path, method="GET", payload=None, headers=None):
        request_headers = {}
        if headers:
            request_headers.update(headers)
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = Request(f"{cls.base_url}{path}", data=data, headers=request_headers, method=method)
        with urlopen(request, timeout=10) as response:
            return response.status, response.read().decode("utf-8"), response.headers

    def test_admin_routes_require_auth(self):
        with self.assertRaises(HTTPError) as ctx:
            self.request_json("/api/devices")
        self.assertEqual(ctx.exception.code, 401)

    def test_device_enroll_and_reconnect(self):
        payload = {
            "name": "Pixel Test",
            "ownerEmail": "pixel@test.local",
            "platform": "android",
            "deviceToken": "device-token-1",
            "pushToken": "push-token-1",
        }
        status, body = self.request_json(
            "/api/devices",
            method="POST",
            payload=payload,
            headers={"X-Tracker-Device-Token": self.device_token},
        )
        self.assertEqual(status, 201)
        self.assertRegex(body["device"]["id"], r"^\d{16}$")

        status, body_again = self.request_json(
            "/api/devices",
            method="POST",
            payload=payload,
            headers={"X-Tracker-Device-Token": self.device_token},
        )
        self.assertEqual(status, 200)
        self.assertEqual(body["device"]["id"], body_again["device"]["id"])

    def test_command_lifecycle(self):
        _, device = self.request_json(
            "/api/devices",
            method="POST",
            payload={
                "name": "Command Phone",
                "ownerEmail": "command@test.local",
                "platform": "android",
                "deviceToken": "device-token-2",
            },
            headers={"X-Tracker-Device-Token": self.device_token},
        )
        device_id = device["device"]["id"]

        status, command_body = self.request_json(
            f"/api/devices/{device_id}/commands",
            method="POST",
            payload={"commandType": "request_details"},
            headers={"Authorization": f"Bearer {self.admin_token}"},
        )
        self.assertEqual(status, 201)
        command_id = command_body["commandId"]

        status, pending = self.request_json(
            f"/api/devices/{device_id}/commands",
            headers={"X-Tracker-Device-Token": self.device_token},
        )
        self.assertEqual(status, 200)
        self.assertEqual(len(pending["commands"]), 1)
        self.assertEqual(pending["commands"][0]["id"], command_id)

        status, complete = self.request_json(
            f"/api/devices/{device_id}/commands/{command_id}/complete",
            method="POST",
            payload={"notes": "done"},
            headers={"X-Tracker-Device-Token": self.device_token},
        )
        self.assertEqual(status, 200)
        self.assertTrue(complete["ok"])

    def test_hourly_report_and_csv_export(self):
        _, device = self.request_json(
            "/api/devices",
            method="POST",
            payload={
                "name": "Hourly Phone",
                "ownerEmail": "hourly@test.local",
                "platform": "android",
                "deviceToken": "device-token-3",
            },
            headers={"X-Tracker-Device-Token": self.device_token},
        )
        device_id = device["device"]["id"]

        status, hourly = self.request_json(
            f"/api/devices/{device_id}/hourly-reports",
            method="POST",
            payload={
                "hourKey": "2026-04-07-19",
                "status": "success",
                "capturedAt": "2026-04-07T13:15:00Z",
                "deviceTimeZone": "Asia/Kathmandu",
                "deviceTimestampMs": 1775567700000,
                "latitude": 27.7,
                "longitude": 85.3,
                "lastKnownLatitude": 27.7,
                "lastKnownLongitude": 85.3,
                "accuracyM": 12.5,
                "batteryLevel": 77,
                "networkStatus": "wifi",
            },
            headers={"X-Tracker-Device-Token": self.device_token},
        )
        self.assertEqual(status, 201)
        self.assertEqual(hourly["hourKey"], "2026-04-07-19")

        status, csv_text, response_headers = self.request_text(
            f"/api/devices/{device_id}/hourly-report.csv",
            headers={"Authorization": f"Bearer {self.admin_token}"},
        )
        self.assertEqual(status, 200)
        self.assertIn("device_id,device_name,status,reason,date,time", csv_text)
        self.assertIn(device_id, csv_text)
        self.assertIn("Hourly Phone", csv_text)
        self.assertIn("success", csv_text)
        self.assertIn("attachment;", response_headers.get("Content-Disposition", ""))

    def test_admin_can_rename_device(self):
        _, device = self.request_json(
            "/api/devices",
            method="POST",
            payload={
                "name": "Rename Phone",
                "ownerEmail": "rename@test.local",
                "platform": "android",
                "deviceToken": "device-token-4",
            },
            headers={"X-Tracker-Device-Token": self.device_token},
        )
        device_id = device["device"]["id"]

        status, renamed = self.request_json(
            f"/api/devices/{device_id}/rename",
            method="POST",
            payload={"name": "Field Unit Alpha"},
            headers={"Authorization": f"Bearer {self.admin_token}"},
        )
        self.assertEqual(status, 200)
        self.assertTrue(renamed["ok"])
        self.assertEqual(renamed["device"]["name"], "Field Unit Alpha")

        status, refreshed = self.request_json(
            f"/api/devices/{device_id}",
            headers={"Authorization": f"Bearer {self.admin_token}"},
        )
        self.assertEqual(status, 200)
        self.assertEqual(refreshed["device"]["name"], "Field Unit Alpha")


if __name__ == "__main__":
    unittest.main()
