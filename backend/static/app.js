const state = {
  devices: [],
  isLoading: false,
  lastRenderSignature: "",
  serverTimeMs: 0,
  reachabilityByDeviceId: {},
};
const AUTO_REFRESH_MS = 2_000;
const ENTER_LIVE_MS = 15_000;
const EXIT_LIVE_MS = 25_000;
const ADMIN_TOKEN_STORAGE_KEY = "trackerAdminToken";

function getAdminToken() {
  return window.localStorage.getItem(ADMIN_TOKEN_STORAGE_KEY) || "";
}

function setAdminToken(token) {
  const value = String(token || "").trim();
  if (value) {
    window.localStorage.setItem(ADMIN_TOKEN_STORAGE_KEY, value);
  } else {
    window.localStorage.removeItem(ADMIN_TOKEN_STORAGE_KEY);
  }
}

function syncAdminTokenInput() {
  const input = document.getElementById("admin-token-input");
  const saveButton = document.getElementById("save-token-btn");
  const clearButton = document.getElementById("clear-token-btn");
  const token = getAdminToken();
  if (input && document.activeElement !== input) {
    input.value = "";
  }
  if (saveButton) {
    saveButton.textContent = token ? "Authenticated" : "Login";
  }
  if (clearButton) {
    clearButton.disabled = !token;
  }
}

async function request(path, options = {}, retryOnUnauthorized = true) {
  const headers = {
    "Content-Type": "application/json",
    ...(options.headers || {}),
  };
  const adminToken = getAdminToken();
  if (adminToken) {
    headers.Authorization = `Bearer ${adminToken}`;
  }
  const response = await fetch(path, {
    headers,
    ...options,
  });
  if (response.status === 401 && retryOnUnauthorized) {
    const token = getAdminToken();
    if (token) {
      return request(path, options, false);
    }
  }
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error || "Request failed");
  }
  return data;
}

async function downloadCsvReport(deviceId) {
  const headers = {};
  const adminToken = getAdminToken();
  if (adminToken) {
    headers.Authorization = `Bearer ${adminToken}`;
  }
  let response = await fetch(`/api/devices/${deviceId}/hourly-report.csv`, { headers });
  if (response.status === 401) {
    const token = getAdminToken();
    if (!token) {
      throw new Error("admin authentication required");
    }
    response = await fetch(`/api/devices/${deviceId}/hourly-report.csv`, {
      headers: { Authorization: `Bearer ${token}` },
    });
  }
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(errorText || "Report download failed");
  }
  const blob = await response.blob();
  const url = window.URL.createObjectURL(blob);
  const link = document.createElement("a");
  const disposition = response.headers.get("Content-Disposition") || "";
  const filenameMatch = disposition.match(/filename="([^"]+)"/);
  link.href = url;
  link.download = filenameMatch ? filenameMatch[1] : `${deviceId}-hourly-report.csv`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(url);
}

function formatTime(value) {
  if (!value) return "Never";
  return new Date(value).toLocaleString();
}

function isRecent(value, windowMs = EXIT_LIVE_MS) {
  if (!value) return false;
  const nowMs = state.serverTimeMs || Date.now();
  return nowMs - new Date(value).getTime() <= windowMs;
}

function getHeartbeatTime(device) {
  return device.lastSeenAt || device.updatedAt;
}

function getFormattedRelativeAge(value) {
  if (!value) return "No contact yet";
  const deltaMs = Math.max(0, (state.serverTimeMs || Date.now()) - new Date(value).getTime());
  const seconds = Math.floor(deltaMs / 1000);
  if (seconds < 5) return "just now";
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

function isDeviceLive(device) {
  const heartbeatTime = getHeartbeatTime(device);
  const deviceId = device.id;
  const previouslyLive = state.reachabilityByDeviceId[deviceId] === "live";
  const thresholdMs = previouslyLive ? EXIT_LIVE_MS : ENTER_LIVE_MS;
  const live = isRecent(heartbeatTime, thresholdMs);
  state.reachabilityByDeviceId[deviceId] = live ? "live" : "attention";
  return live;
}

function getDeviceStateMessage(device) {
  const heartbeatTime = getHeartbeatTime(device);
  if (isDeviceLive(device)) {
    return "Connected";
  }
  if (heartbeatTime && !isRecent(heartbeatTime, 6 * 60 * 60 * 1000)) {
    return `Device unreachable since ${formatTime(heartbeatTime)}`;
  }
  return heartbeatTime
    ? `Connection lost since ${formatTime(heartbeatTime)}`
    : "Connection lost";
}

function getLastKnownLocation(device) {
  if (device.lastLatitude == null || device.lastLongitude == null) {
    return "Last known location unavailable";
  }
  const lastPing = getLastLocationPing(device);
  const suffix = lastPing ? ` • ${getFormattedRelativeAge(lastPing.createdAt)}` : "";
  return `Last known location: ${device.lastLatitude.toFixed(5)}, ${device.lastLongitude.toFixed(5)}${suffix}`;
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
    return device.wifiSsid ? `Wi-Fi • ${device.wifiSsid}` : "Wi-Fi";
  }
  if (device.networkStatus === "cellular") {
    return device.carrierName ? `Cellular • ${device.carrierName}` : "Cellular";
  }
  if (device.networkStatus === "online") {
    return "Online";
  }
  return device.networkStatus || "Unknown";
}

function getOptionalDeviceValue(value) {
  return value || "Unavailable";
}

function getLocationSummary(device, stateMessage) {
  const latestCompletion = getLatestCommandCompletion(device);
  const completionNotes = String(latestCompletion?.notes || "").toLowerCase();
  if (completionNotes.includes("recent last-known location returned")) {
    return "Cached position returned";
  }
  if (stateMessage !== "Connected") {
    return device.lastLatitude != null && device.lastLongitude != null
      ? "Device unreachable, showing last known position"
      : stateMessage;
  }
  if (device.lastLatitude != null && device.lastLongitude != null) {
    return `Current position • ${device.lastLatitude.toFixed(5)}, ${device.lastLongitude.toFixed(5)}`;
  }
  return "No location yet";
}

function getDeviceMeta(device, stateMessage) {
  return `${device.platform.toUpperCase()} • ${device.ownerEmail} • ${stateMessage}`;
}

function getPendingCommandCount(device) {
  return (device.actions || []).filter((entry) => {
    const label = String(entry.actionType || "");
    return label === "request_location" || label === "request_details";
  }).length;
}

function getActionEntries(device, actionType) {
  return (device.actions || []).filter((entry) => String(entry.actionType || "") === actionType);
}

function getLatestAction(device, actionType) {
  return getActionEntries(device, actionType)
    .slice()
    .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))[0] || null;
}

function getLatestCommandRequest(device) {
  return (device.actions || [])
    .filter((entry) => ["request_location", "request_details"].includes(String(entry.actionType || "")))
    .slice()
    .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))[0] || null;
}

function getLatestCommandCompletion(device) {
  return getLatestAction(device, "command_completed");
}

function getLastLocationPing(device) {
  return (device.history || [])
    .slice()
    .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))[0] || null;
}

function getLocationHealthLabel(device) {
  const latestDisabled = getLatestAction(device, "location_disabled");
  if (latestDisabled && (!device.locationServicesEnabled || isRecent(latestDisabled.createdAt, 6 * 60 * 60 * 1000))) {
    return `Location blocked since ${getFormattedRelativeAge(latestDisabled.createdAt)}`;
  }
  const latestCompletion = getLatestCommandCompletion(device);
  const completionNotes = String(latestCompletion?.notes || "").toLowerCase();
  if (completionNotes.includes("recent last-known location returned")) {
    return `Last request used cached location ${getFormattedRelativeAge(latestCompletion.createdAt)}`;
  }
  if (completionNotes.includes("no fresh or recent location fix available")) {
    return `Last request missed a fix ${getFormattedRelativeAge(latestCompletion.createdAt)}`;
  }
  const lastPing = getLastLocationPing(device);
  if (lastPing) {
    return `Last location ping ${getFormattedRelativeAge(lastPing.createdAt)}`;
  }
  return "No location pings yet";
}

function getCommandHealthLabel(device) {
  const latestRequest = getLatestCommandRequest(device);
  const latestCompletion = getLatestCommandCompletion(device);
  if (!latestRequest) {
    return "No recent dashboard commands";
  }
  if (!latestCompletion) {
    return `Last command waiting since ${getFormattedRelativeAge(latestRequest.createdAt)}`;
  }
  const requestTime = new Date(latestRequest.createdAt).getTime();
  const completionTime = new Date(latestCompletion.createdAt).getTime();
  if (completionTime >= requestTime) {
    if (String(latestCompletion.notes || "").toLowerCase().includes("recent last-known location returned")) {
      return `Last command used cached location ${getFormattedRelativeAge(latestCompletion.createdAt)}`;
    }
    return `Last command completed ${getFormattedRelativeAge(latestCompletion.createdAt)}`;
  }
  return `Last command waiting since ${getFormattedRelativeAge(latestRequest.createdAt)}`;
}

function getDeviceOpsItems(device) {
  const heartbeatTime = getHeartbeatTime(device);
  const commandRequests = (device.actions || []).filter((entry) =>
    ["request_location", "request_details"].includes(String(entry.actionType || ""))
  ).length;
  const commandCompletions = getActionEntries(device, "command_completed").length;
  const reconnects = getActionEntries(device, "reconnected").length;
  return [
    {
      label: "Heartbeat",
      value: heartbeatTime ? getFormattedRelativeAge(heartbeatTime) : "Never",
      tone: isDeviceLive(device) ? "ok" : "warn",
    },
    {
      label: "Location health",
      value: getLocationHealthLabel(device),
      tone: device.locationServicesEnabled ? "neutral" : "warn",
    },
    {
      label: "Command flow",
      value: getCommandHealthLabel(device),
      tone: getPendingCommandCount(device) > 0 ? "warn" : "ok",
    },
    {
      label: "Background mode",
      value: device.batteryOptimizationExempt ? "Battery unrestricted" : "Battery optimized",
      tone: device.batteryOptimizationExempt ? "ok" : "warn",
    },
    {
      label: "Compatibility",
      value: device.compatibilityProfile || "Unknown",
      tone: "neutral",
    },
    {
      label: "Recent ops",
      value: `${commandCompletions}/${commandRequests} commands completed${reconnects ? ` • ${reconnects} reconnects` : ""}`,
      tone: commandCompletions < commandRequests ? "warn" : "neutral",
    },
  ];
}

function statItem(label, value, extraClass = "") {
  return `<div class="stat-item ${extraClass}"><span class="stat-label">${label}</span><strong class="stat-value">${value}</strong></div>`;
}

function opsItem(label, value, tone = "neutral") {
  return `<div class="ops-item" data-tone="${tone}"><span class="ops-label">${label}</span><strong class="ops-value">${escapeHtml(value)}</strong></div>`;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function renderDevices() {
  const list = document.getElementById("device-list");
  const count = document.getElementById("system-count");
  const template = document.getElementById("device-template");
  const status = document.getElementById("dashboard-status");
  const liveCount = document.getElementById("live-count");
  const attentionCount = document.getElementById("attention-count");
  const pendingCount = document.getElementById("pending-count");
  const dashboardClock = document.getElementById("dashboard-clock");

  const liveDevices = state.devices.filter((device) => isDeviceLive(device));
  const totalPendingCommands = state.devices.reduce((sum, device) => sum + getPendingCommandCount(device), 0);

  list.innerHTML = "";
  count.textContent = `${state.devices.length} device${state.devices.length === 1 ? "" : "s"}`;
  if (liveCount) liveCount.textContent = String(liveDevices.length);
  if (attentionCount) attentionCount.textContent = String(Math.max(0, state.devices.length - liveDevices.length));
  if (pendingCount) pendingCount.textContent = String(totalPendingCommands);
  if (dashboardClock) {
    dashboardClock.textContent = state.serverTimeMs
      ? `Server time ${new Date(state.serverTimeMs).toLocaleTimeString()}`
      : "Waiting for sync";
  }
  if (status) {
    status.textContent = state.devices.length
      ? `Loaded ${state.devices.length} device${state.devices.length === 1 ? "" : "s"}.`
      : "No devices reported yet.";
  }

  if (state.devices.length === 0) {
    const empty = document.createElement("article");
    empty.className = "device-card";
    empty.innerHTML = `
      <div class="device-top">
        <div>
          <h3 class="device-name">No devices yet</h3>
          <p class="device-meta">Waiting for phones to contact the backend.</p>
        </div>
        <span class="status-pill" data-status="attention">idle</span>
      </div>
    `;
    list.appendChild(empty);
    return;
  }

  state.devices.forEach((device) => {
    try {
      const node = template.content.firstElementChild.cloneNode(true);
      const stateMessage = getDeviceStateMessage(device);
      const heartbeatTime = getHeartbeatTime(device);
      const isFresh = isDeviceLive(device);
      const isHealthy = isFresh;
      node.querySelector(".device-name").textContent = device.name;
      node.querySelector(".device-meta").textContent = getDeviceMeta(device, stateMessage);
      node.querySelector(".device-banner-state").textContent = stateMessage;
      node.querySelector(".device-banner-time").textContent = getFormattedRelativeAge(heartbeatTime);
      const pill = node.querySelector(".status-pill");
      pill.textContent = isHealthy ? "connected" : "attention";
      pill.dataset.status = isHealthy ? "live" : "attention";

      node.querySelector(".device-stats").innerHTML = [
        statItem("Device ID", device.id),
        statItem("Battery", `${device.batteryLevel}%${device.isCharging ? " charging" : ""}`),
        statItem("Background", device.batteryOptimizationExempt ? "Unrestricted" : "Optimized", device.batteryOptimizationExempt ? "" : "stat-warning"),
        statItem("Network", getNetworkLabel(device, isFresh)),
        statItem("Carrier", getOptionalDeviceValue(device.carrierName)),
        statItem("Commands", `${getPendingCommandCount(device)} recent`),
        statItem("Local IP", getOptionalDeviceValue(device.localIp)),
        statItem("IPv6", getOptionalDeviceValue(device.localIpv6)),
        statItem("Public IP", getOptionalDeviceValue(device.publicIp)),
        statItem("ISP", getOptionalDeviceValue(device.ispName)),
        statItem("Location", device.locationServicesEnabled ? "Enabled" : "Disabled", device.locationServicesEnabled ? "" : "stat-warning"),
        statItem("Updated", formatTime(heartbeatTime), !isFresh ? "stat-warning" : "")
      ].join("");

      node.querySelector(".observability-strip").innerHTML = getDeviceOpsItems(device)
        .map((item) => opsItem(item.label, item.value, item.tone))
        .join("");

      const coordsNode = node.querySelector(".coords");
      const mapsUrl = getGoogleMapsUrl(device);
      const locationSummary = getLocationSummary(device, stateMessage);
      const lastKnown = stateMessage ? getLastKnownLocation(device) : "Open in Google Maps";
      const locationMarkup = `<span class="coords-label">${locationSummary}</span><span class="coords-subtext">${lastKnown}</span>`;
      if (mapsUrl) {
        coordsNode.innerHTML = `<a href="${mapsUrl}" target="_blank" rel="noopener noreferrer">${locationMarkup}</a>`;
      } else {
        coordsNode.innerHTML = locationMarkup;
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
      ].sort((a, b) => new Date(b.time) - new Date(a.time)).slice(0, 100);

      history.innerHTML = mergedEvents.length
        ? mergedEvents.map((entry) => `<li><span>${entry.label}</span><time>${formatTime(entry.time)}</time></li>`).join("")
        : "<li><span>No events yet.</span><time>Waiting</time></li>";

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
        downloadButton.addEventListener("click", async () => {
          try {
            await downloadCsvReport(device.id);
          } catch (error) {
            alert(error.message);
          }
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
            <h3 class="device-name">${escapeHtml(device.name || device.id || "Unknown device")}</h3>
            <p class="device-meta">This device hit a render fallback, but it is still present in the API.</p>
          </div>
          <span class="status-pill" data-status="attention">attention</span>
        </div>
        <div class="device-stats">
          ${statItem("Device ID", escapeHtml(device.id || "unknown"))}
          ${statItem("Updated", escapeHtml(formatTime(getHeartbeatTime(device))))}
          ${statItem("Network", escapeHtml(device.networkStatus || "unknown"))}
        </div>
        <pre style="white-space: pre-wrap; overflow-wrap: anywhere;">${escapeHtml(JSON.stringify(device, null, 2))}</pre>
      `;
      list.appendChild(fallback);
    }
  });
}

async function loadDevices() {
  const status = document.getElementById("dashboard-status");
  if (state.isLoading) {
    return "busy";
  }
  state.isLoading = true;
  if (status) {
    status.textContent = "Loading devices...";
  }
  try {
    const data = await request("/api/devices?includeHistory=true");
    state.serverTimeMs = data.serverTime ? new Date(data.serverTime).getTime() : Date.now();
    const nextSignature = JSON.stringify(data.devices);
    if (nextSignature === state.lastRenderSignature) {
      if (status) {
        status.textContent = `Loaded ${data.devices.length} device${data.devices.length === 1 ? "" : "s"}.`;
      }
      return "unchanged";
    }
    state.devices = data.devices;
    state.lastRenderSignature = nextSignature;
    renderDevices();
    return "updated";
  } catch (error) {
    if (status) {
      status.textContent = `Dashboard load failed: ${error.message}`;
    }
    throw error;
  } finally {
    state.isLoading = false;
  }
}

document.getElementById("refresh-btn").addEventListener("click", async (event) => {
  const button = event.currentTarget;
  const originalLabel = button.textContent;
  button.textContent = "Refreshing...";
  try {
    const result = await loadDevices();
    if (result === "updated") {
      button.textContent = "Updated";
    } else if (result === "unchanged") {
      button.textContent = "No change";
    } else if (result === "busy") {
      button.textContent = "Busy";
    } else {
      button.textContent = "Refreshed";
    }
  } catch {
    button.textContent = "Refresh failed";
  } finally {
    window.setTimeout(() => {
      button.textContent = originalLabel;
    }, 1500);
  }
});

document.getElementById("save-token-btn").addEventListener("click", async () => {
  const input = document.getElementById("admin-token-input");
  setAdminToken(input ? input.value : "");
  if (input) {
    input.value = "";
  }
  syncAdminTokenInput();
  try {
    await loadDevices();
  } catch (error) {
    const button = document.getElementById("save-token-btn");
    if (button) {
      button.textContent = "Login failed";
      window.setTimeout(() => syncAdminTokenInput(), 1500);
    }
  }
});

document.getElementById("clear-token-btn").addEventListener("click", () => {
  setAdminToken("");
  syncAdminTokenInput();
});

document.getElementById("admin-token-input").addEventListener("keydown", async (event) => {
  if (event.key !== "Enter") {
    return;
  }
  event.preventDefault();
  document.getElementById("save-token-btn").click();
});

window.setInterval(loadDevices, AUTO_REFRESH_MS);

syncAdminTokenInput();
loadDevices().catch((error) => {
  const status = document.getElementById("dashboard-status");
  if (status) {
    status.textContent = `Dashboard load failed: ${error.message}`;
  }
});
