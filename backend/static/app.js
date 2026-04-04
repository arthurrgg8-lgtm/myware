const state = {
  devices: [],
};
const AUTO_REFRESH_MS = 5_000;

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error || "Request failed");
  }
  return data;
}

function formatTime(value) {
  if (!value) return "Never";
  return new Date(value).toLocaleString();
}

function isRecent(value, windowMs = 130000) {
  if (!value) return false;
  return Date.now() - new Date(value).getTime() <= windowMs;
}

function getDeviceStateMessage(device) {
  const isFresh = isRecent(device.updatedAt);
  const issues = [];
  if (!device.locationServicesEnabled) {
    issues.push("Location is turned off on device");
  }
  if (!isFresh && device.networkStatus === "offline") {
    issues.push("Network is not connected on device");
  }
  if (!isFresh && issues.length === 0) {
    issues.push("No recent updates received from device");
  }
  return issues.join(" • ");
}

function getLastKnownLocation(device) {
  if (device.lastLatitude == null || device.lastLongitude == null) {
    return "Last known location unavailable";
  }
  return `Last known location: ${device.lastLatitude.toFixed(5)}, ${device.lastLongitude.toFixed(5)}`;
}

function getGoogleMapsUrl(device) {
  if (device.lastLatitude == null || device.lastLongitude == null) {
    return null;
  }
  return `https://www.google.com/maps?q=${device.lastLatitude},${device.lastLongitude}`;
}

function getNetworkLabel(device, isFresh) {
  if (!isFresh && device.networkStatus === "offline") {
    return "not connected";
  }
  if (device.networkStatus === "wifi") {
    return device.wifiSsid ? `wifi (${device.wifiSsid})` : "wifi";
  }
  if (device.networkStatus === "cellular") {
    return device.carrierName ? `cellular (${device.carrierName})` : "cellular";
  }
  return device.networkStatus;
}

function getOptionalDeviceValue(value) {
  return value || "unavailable on this device";
}

function renderDevices() {
  const list = document.getElementById("device-list");
  const count = document.getElementById("system-count");
  const template = document.getElementById("device-template");

  list.innerHTML = "";
  count.textContent = `${state.devices.length} device${state.devices.length === 1 ? "" : "s"}`;

  state.devices.forEach((device) => {
    try {
      const node = template.content.firstElementChild.cloneNode(true);
      const stateMessage = getDeviceStateMessage(device);
      const isFresh = isRecent(device.updatedAt);
      node.querySelector(".device-name").textContent = device.name;
      node.querySelector(".device-meta").textContent = stateMessage
        ? `${device.platform.toUpperCase()} • ${device.ownerEmail} • ${stateMessage}`
        : `${device.platform.toUpperCase()} • ${device.ownerEmail} • Last seen ${formatTime(device.lastSeenAt)}`;
      node.querySelector(".status-pill").textContent = device.status;
      node.querySelector(".status-pill").dataset.status = device.status;
      node.querySelector(".device-stats").innerHTML = `
        <div><strong>Device ID:</strong> ${device.id}</div>
        <div><strong>Battery:</strong> ${device.batteryLevel}%${device.isCharging ? " charging" : ""}</div>
        <div><strong>Network:</strong> ${getNetworkLabel(device, isFresh)}</div>
        <div><strong>Carrier:</strong> ${getOptionalDeviceValue(device.carrierName)}</div>
        <div><strong>Local IP:</strong> ${getOptionalDeviceValue(device.localIp)}</div>
        <div><strong>Public IP:</strong> ${getOptionalDeviceValue(device.publicIp)}</div>
        <div><strong>ISP:</strong> ${getOptionalDeviceValue(device.ispName)}</div>
        <div><strong>Location:</strong> ${device.locationServicesEnabled ? "enabled" : "disabled on device"}</div>
        <div><strong>Updated:</strong> ${formatTime(device.updatedAt)}</div>
      `;

      const coords = stateMessage
        ? `${stateMessage} • ${getLastKnownLocation(device)}`
        : device.lastLatitude != null && device.lastLongitude != null
        ? `${device.lastLatitude.toFixed(5)}, ${device.lastLongitude.toFixed(5)}`
        : "No location yet";
      const coordsNode = node.querySelector(".coords");
      const mapsUrl = getGoogleMapsUrl(device);
      if (mapsUrl) {
        coordsNode.innerHTML = `<a href="${mapsUrl}" target="_blank" rel="noopener noreferrer">${coords}</a>`;
      } else {
        coordsNode.textContent = coords;
      }

      const history = node.querySelector(".history-list");
      const mergedEvents = [
        ...(device.actions || []).map((entry) => ({
          time: entry.createdAt,
          label: `${entry.actionType.replaceAll("_", " ")}${entry.notes ? `: ${entry.notes}` : ""}`,
        })),
        ...(device.history || []).map((entry) => ({
          time: entry.createdAt,
          label: `location ping at ${entry.latitude.toFixed(4)}, ${entry.longitude.toFixed(4)} • ${entry.batteryLevel ?? "?"}%`,
        })),
      ].sort((a, b) => new Date(b.time) - new Date(a.time)).slice(0, 6);

      history.innerHTML = mergedEvents.length
        ? mergedEvents.map((entry) => `<li><span>${entry.label}</span><time>${formatTime(entry.time)}</time></li>`).join("")
        : "<li>No events yet.</li>";

      node.querySelectorAll("[data-command]").forEach((button) => {
        button.addEventListener("click", async () => {
          try {
            await request(`/api/devices/${device.id}/commands`, {
              method: "POST",
              body: JSON.stringify({ commandType: button.dataset.command }),
            });
            await loadDevices();
          } catch (error) {
            alert(error.message);
          }
        });
      });

      const removeButton = node.querySelector("[data-remove-device]");
      if (removeButton) {
        removeButton.addEventListener("click", async () => {
          const confirmed = window.confirm(`Remove device ${device.id}?`);
          if (!confirmed) {
            return;
          }
          try {
            await request(`/api/devices/${device.id}`, {
              method: "DELETE",
            });
            await loadDevices();
          } catch (error) {
            alert(error.message);
          }
        });
      }

      const downloadButton = node.querySelector("[data-download-report]");
      if (downloadButton) {
        downloadButton.addEventListener("click", () => {
          window.location.href = `/api/devices/${device.id}/hourly-report.csv`;
        });
      }

      list.appendChild(node);
    } catch (error) {
      console.error("Failed to render device", device, error);
      const fallback = document.createElement("article");
      fallback.className = "device-card";
      fallback.innerHTML = `
        <div class="device-top">
          <div>
            <h3 class="device-name">${device.name || device.id || "Unknown device"}</h3>
            <p class="device-meta">This device hit a render fallback, but it is still present in the API.</p>
          </div>
          <span class="status-pill">${device.status || "active"}</span>
        </div>
        <div class="device-stats">
          <div><strong>Device ID:</strong> ${device.id || "unknown"}</div>
          <div><strong>Updated:</strong> ${formatTime(device.updatedAt)}</div>
          <div><strong>Network:</strong> ${device.networkStatus || "unknown"}</div>
        </div>
      `;
      list.appendChild(fallback);
    }
  });
}

async function loadDevices() {
  const data = await request("/api/devices?includeHistory=true");
  state.devices = data.devices;
  renderDevices();
}

document.getElementById("refresh-btn").addEventListener("click", loadDevices);
window.setInterval(loadDevices, AUTO_REFRESH_MS);

loadDevices().catch((error) => {
  alert(error.message);
});
