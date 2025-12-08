let systems = [];
let ports = [];

async function loadSystems() {
  try {
    const res = await fetch('/api/systems');
    const data = await res.json();
    systems = data.systems;
    const select = document.getElementById('systemType');
    select.innerHTML = '<option value="">Select controller...</option>';
    systems.forEach(s => {
      const opt = document.createElement('option');
      opt.value = s.id;
      opt.textContent = s.name;
      select.appendChild(opt);
    });
  } catch (err) {
    showStatus('Error loading systems: ' + err.message, 'error');
  }
}

async function loadPorts() {
  try {
    const res = await fetch('/api/ports');
    const data = await res.json();
    ports = data.ports;
    const select = document.getElementById('portName');
    const previousSelection = select.value;
    select.innerHTML = '<option value="">Select port...</option>';
    ports.forEach(p => {
      const opt = document.createElement('option');
      opt.value = p;
      opt.textContent = p;
      select.appendChild(opt);
    });
    // Keep the user's selection if the port is still present
    if (previousSelection && ports.includes(previousSelection)) {
      select.value = previousSelection;
    }
  } catch (err) {
    showStatus('Error loading ports: ' + err.message, 'error');
  }
}

document.getElementById('systemType').addEventListener('change', (e) => {
  const system = systems.find(s => s.id === e.target.value);
  if (system && system.connectionTypes.includes('network')) {
    document.getElementById('portGroup').style.display = 'none';
    document.getElementById('serialOptionsGroup').style.display = 'none';
    document.getElementById('networkGroup').style.display = 'block';
  } else {
    document.getElementById('portGroup').style.display = 'block';
    document.getElementById('serialOptionsGroup').style.display = 'block';
    document.getElementById('networkGroup').style.display = 'none';
    // Set default baud rate based on system type
    const baudRateSelect = document.getElementById('baudRate');
    if (e.target.value === 'xnet-elite') {
      baudRateSelect.value = '19200';
    } else {
      baudRateSelect.value = '';
    }
  }
});

document.getElementById('configForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const params = new URLSearchParams();
  params.append('id', document.getElementById('connId').value);
  params.append('systemType', document.getElementById('systemType').value);
  if (document.getElementById('userName').value) {
    params.append('userName', document.getElementById('userName').value);
  }
  if (document.getElementById('systemPrefix').value) {
    params.append('systemPrefix', document.getElementById('systemPrefix').value);
  }
  const system = systems.find(s => s.id === document.getElementById('systemType').value);
  if (system && system.connectionTypes.includes('network')) {
    params.append('host', document.getElementById('host').value);
    params.append('port', document.getElementById('port').value);
  } else {
    params.append('portName', document.getElementById('portName').value);
    const baudRate = document.getElementById('baudRate').value;
    if (baudRate) {
      params.append('baudRate', baudRate);
    }
    const flowControl = document.getElementById('flowControl').value;
    if (flowControl) {
      params.append('flowControl', flowControl);
    }
  }
  try {
    const res = await fetch('/connections/create?' + params, { method: 'POST' });
    const data = await res.json();
    if (res.ok) {
      showStatus('Connection created successfully!', 'success');
      loadConnections();
      document.getElementById('configForm').reset();
    } else {
      showStatus('Error: ' + (data.error || 'Unknown error'), 'error');
    }
  } catch (err) {
    showStatus('Error: ' + err.message, 'error');
  }
});

async function loadConnections() {
  try {
    const res = await fetch('/connections');
    const data = await res.json();
    const list = document.getElementById('connectionsList');
    if (data.connections.length === 0) {
      list.innerHTML = '<p>No active connections</p>';
    } else {
      list.innerHTML = data.connections.map(c => {
        let html = `<div class="connection-item"><strong>${c.id}</strong> (${c.systemType}) - `;
        html += `${c.connected ? '✅ Connected' : '❌ Disconnected'}`;
        if (c.connected && c.commandStation) {
          html += `<br><small>Command Station: `;
          if (c.commandStation.manufacturer) html += `${c.commandStation.manufacturer} `;
          if (c.commandStation.model) html += `${c.commandStation.model} `;
          if (c.commandStation.version) html += `v${c.commandStation.version}`;
          if (c.commandStation.versionString) html += ` (${c.commandStation.versionString})`;
          if (c.commandStation.type && c.commandStation.type !== '-1') html += ` hardware type: ${c.commandStation.type}`;
          if (c.commandStation.softwareVersion && c.commandStation.softwareVersion !== '-1') html += ` software version: ${c.commandStation.softwareVersion}`;
          html += `</small>`;
        }
        if (c.connected && (c.powerStatus || connectionPowerStatus[c.id])) {
          const powerStatus = c.powerStatus || connectionPowerStatus[c.id] || 'UNKNOWN';
          const powerIcon = powerStatus === 'ON' ? '🟢' : powerStatus === 'OFF' ? '🔴' : '🟡';
          html += `<br><small class="power-status">Power: ${powerIcon} ${powerStatus}</small>`;
        }
        if (c.connected) {
          html += `<br><small style="color: #666;">Roles: `;
          const roles = c.roles || [];
          if (roles.length === 0) {
            html += `None`;
          } else {
            html += roles.join(', ');
          }
          html += `</small>`;
          html += `<br><label style="font-size: 12px; color: #cccccc;"><input type="checkbox" ${roles.includes('throttles') ? 'checked' : ''} onchange="setControllerRole('${c.id}', 'throttles', this.checked)"> Throttles</label> `;
          html += `<label style="font-size: 12px; color: #cccccc; margin-left: 10px;"><input type="checkbox" ${roles.includes('accessories') ? 'checked' : ''} onchange="setControllerRole('${c.id}', 'accessories', this.checked)"> Accessories</label>`;
          html += `<br><button onclick="requestVersion('${c.id}')" style="margin-top: 5px; padding: 5px 10px; background: #0e639c; color: white; border: none; border-radius: 3px; cursor: pointer; font-size: 12px;">Get Version</button>`;
        }
        html += `</div>`;
        return html;
      }).join('');
    }
    // Update controller role info
    const throttleControllerId = data.connections.find(c => c.roles && c.roles.includes('throttles'))?.id;
    const accessoryControllerId = data.connections.find(c => c.roles && c.roles.includes('accessories'))?.id;
    const throttleInfo = document.getElementById('throttleControllerInfo');
    const accessoryInfo = document.getElementById('accessoryControllerInfo');
    if (throttleControllerId) {
      const conn = data.connections.find(c => c.id === throttleControllerId);
      throttleInfo.textContent = conn ? (conn.id + ' (' + conn.systemType + ')') : throttleControllerId;
    } else {
      throttleInfo.textContent = 'No controller assigned';
    }
    if (accessoryControllerId) {
      const conn = data.connections.find(c => c.id === accessoryControllerId);
      accessoryInfo.textContent = conn ? (conn.id + ' (' + conn.systemType + ')') : accessoryControllerId;
    } else {
      accessoryInfo.textContent = 'No controller assigned';
    }
  } catch (err) {
    document.getElementById('connectionsList').innerHTML = '<p>Error loading connections</p>';
  }
}

let currentThrottleId = null;
let throttleState = { speed: 0, forward: true, functions: {} };

async function openThrottle() {
  const address = parseInt(document.getElementById('throttleAddress').value);
  const longAddress = document.getElementById('throttleLongAddress').checked;
  if (!address) {
    showStatus('Please enter an address', 'error');
    return;
  }
  try {
    const res = await fetch('/api/throttles?address=' + address + '&longAddress=' + longAddress, { method: 'POST' });
    const data = await res.json();
    if (res.ok) {
      currentThrottleId = data.id;
      document.getElementById('throttleControls').style.display = 'block';
      document.getElementById('closeThrottleBtn').style.display = 'inline-block';
      updateThrottleStatus('Throttle opened for train ' + address);
      showStatus('Throttle opened successfully', 'success');
      initThrottleFunctions();
    } else {
      showStatus('Error: ' + (data.error || 'Unknown error'), 'error');
    }
  } catch (err) {
    showStatus('Error: ' + err.message, 'error');
  }
}

async function closeThrottle() {
  if (!currentThrottleId) return;
  try {
    const res = await fetch('/api/throttles/' + encodeURIComponent(currentThrottleId), { method: 'DELETE' });
    if (res.ok) {
      currentThrottleId = null;
      document.getElementById('throttleControls').style.display = 'none';
      document.getElementById('closeThrottleBtn').style.display = 'none';
      throttleState = { speed: 0, forward: true, functions: {} };
      updateThrottleStatus('Throttle closed');
      showStatus('Throttle closed', 'success');
    }
  } catch (err) {
    showStatus('Error: ' + err.message, 'error');
  }
}

function initThrottleFunctions() {
  const container = document.getElementById('throttleFunctions');
  container.innerHTML = '';
  for (let i = 0; i <= 12; i++) {
    const btn = document.createElement('button');
    btn.id = 'funcBtn' + i;
    btn.textContent = 'F' + i;
    if (i === 2) {
      // F2 is momentary - use mousedown/mouseup
      btn.onmousedown = () => setFunction(i, true);
      btn.onmouseup = () => setFunction(i, false);
      btn.onmouseleave = () => setFunction(i, false);
    } else {
      // Other functions are toggle
      btn.onclick = () => toggleFunction(i);
    }
    container.appendChild(btn);
  }
}

async function toggleFunction(funcNum) {
  if (!currentThrottleId) return;
  const currentState = throttleState.functions[funcNum] || false;
  await setFunction(funcNum, !currentState);
}

async function setFunction(funcNum, on) {
  if (!currentThrottleId) return;
  try {
    const res = await fetch('/api/throttles/' + encodeURIComponent(currentThrottleId) + '/function?number=' + funcNum + '&on=' + on, { method: 'POST' });
    if (res.ok) {
      throttleState.functions[funcNum] = on;
      updateFunctionButton(funcNum, on);
    }
  } catch (err) {
    console.error('Error setting function:', err);
  }
}

function updateFunctionButton(funcNum, on) {
  const btn = document.getElementById('funcBtn' + funcNum);
  if (btn) {
    if (on) {
      btn.style.background = '#0e639c';
      btn.style.color = 'white';
    } else {
      btn.style.background = '#3e3e42';
      btn.style.color = '#cccccc';
    }
  }
}

async function toggleDirection() {
  if (!currentThrottleId) return;
  const newForward = !throttleState.forward;
  try {
    const res = await fetch('/api/throttles/' + encodeURIComponent(currentThrottleId) + '/direction?forward=' + newForward, { method: 'POST' });
    if (res.ok) {
      throttleState.forward = newForward;
      const btn = document.getElementById('throttleDirectionBtn');
      btn.textContent = newForward ? 'Forward' : 'Reverse';
    }
  } catch (err) {
    console.error('Error setting direction:', err);
  }
}

async function updateThrottleSpeed(value) {
  if (!currentThrottleId) return;
  const speedPercent = parseInt(value);
  document.getElementById('throttleSpeedValue').textContent = speedPercent + '%';
  const speedNormalized = speedPercent / 100.0;
  try {
    const res = await fetch('/api/throttles/' + encodeURIComponent(currentThrottleId) + '/speed?value=' + speedNormalized, { method: 'POST' });
    if (res.ok) {
      throttleState.speed = speedNormalized;
    }
  } catch (err) {
    console.error('Error setting speed:', err);
  }
}

function updateThrottleStatus(message) {
  const statusDiv = document.getElementById('throttleStatus');
  const time = new Date().toLocaleTimeString();
  statusDiv.textContent = '[' + time + '] ' + message;
}

async function setAccessory(closed) {
  const address = parseInt(document.getElementById('accessoryAddress').value);
  if (!address) {
    showStatus('Please enter an address', 'error');
    return;
  }
  try {
    const res = await fetch('/api/accessories?address=' + address + '&closed=' + closed, { method: 'POST' });
    const data = await res.json();
    if (res.ok) {
      const state = closed ? 'CLOSED' : 'THROWN';
      updateAccessoryStatus('Accessory ' + address + ' set to ' + state);
      showStatus('Accessory ' + address + ' set to ' + state, 'success');
    } else {
      showStatus('Error: ' + (data.error || 'Unknown error'), 'error');
    }
  } catch (err) {
    showStatus('Error: ' + err.message, 'error');
  }
}

function updateAccessoryStatus(message) {
  const statusDiv = document.getElementById('accessoryStatus');
  const time = new Date().toLocaleTimeString();
  statusDiv.textContent = '[' + time + '] ' + message;
}

function showStatus(msg, type) {
  const div = document.getElementById('status');
  div.className = 'status ' + type;
  div.textContent = msg;
  setTimeout(() => div.textContent = '', 5000);
}

async function requestVersion(connectionId) {
  try {
    const res = await fetch('/connections/requestVersion?id=' + encodeURIComponent(connectionId), { method: 'POST' });
    const data = await res.json();
    if (res.ok) {
      showStatus('Version request sent. Waiting for response...', 'success');
      // Reload connections after a short delay to show updated version
      setTimeout(() => loadConnections(), 1000);
    } else {
      showStatus('Error: ' + (data.error || 'Unknown error'), 'error');
    }
  } catch (err) {
    showStatus('Error: ' + err.message, 'error');
  }
}

let eventSource = null;
let connectionPowerStatus = {};

function connectEventStream() {
  if (eventSource) {
    eventSource.close();
  }
  eventSource = new EventSource('/api/events');
  eventSource.onmessage = function(event) {
    try {
      const data = JSON.parse(event.data);
      handleEvent(data);
    } catch (e) {
      console.error('Error parsing event:', e);
    }
  };
  eventSource.onerror = function(e) {
    console.error('EventSource error:', e);
    // Try to reconnect after 3 seconds
    setTimeout(connectEventStream, 3000);
  };
}

function handleEvent(data) {
  if (data.type === 'MESSAGE_RECEIVED' || data.type === 'MESSAGE_SENT') {
    const decoded = data.payload.decoded || data.payload.message || '';
    const hex = data.payload.hex || '';
    let messageText = `[${data.connectionId}] ${data.payload.direction.toUpperCase()}: `;
    if (decoded && decoded !== data.payload.message) {
      messageText += decoded;
      if (hex) messageText += ` (${hex})`;
    } else {
      messageText += data.payload.message || hex || '';
      if (hex && data.payload.message && hex !== data.payload.message) messageText += ` (${hex})`;
    }
    addConsoleLine(data.type === 'MESSAGE_RECEIVED' ? 'in' : 'out', messageText);
    // If this looks like a version response, reload connections to show updated info
    if (data.type === 'MESSAGE_RECEIVED' && decoded && (decoded.includes('Software Version') || decoded.includes('CS Version') || (hex && hex.includes('63 21')))) {
      setTimeout(() => loadConnections(), 500);
    }
  } else if (data.type === 'THROTTLE_UPDATED') {
    const desc = data.payload.description || `${data.payload.property} = ${data.payload.newValue}`;
    addConsoleLine('power', `[${data.connectionId}] ${desc}`);
    // Update throttle UI if this is for the current throttle
    if (currentThrottleId && currentThrottleId.startsWith(data.connectionId + ':')) {
      const address = data.payload.address;
      const throttleAddress = parseInt(document.getElementById('throttleAddress').value);
      if (address === throttleAddress) {
        updateThrottleStatus(desc);
        const prop = data.payload.property;
        const newVal = data.payload.newValue;
        if (prop === 'SpeedSetting') {
          throttleState.speed = newVal;
          const speedPercent = Math.round(newVal * 100);
          document.getElementById('throttleSpeed').value = speedPercent;
          document.getElementById('throttleSpeedValue').textContent = speedPercent + '%';
        } else if (prop === 'IsForward') {
          throttleState.forward = newVal;
          const btn = document.getElementById('throttleDirectionBtn');
          btn.textContent = newVal ? 'Forward' : 'Reverse';
        } else if (prop && prop.startsWith('F') && !prop.endsWith('Momentary')) {
          const funcNum = parseInt(prop.substring(1));
          if (!isNaN(funcNum)) {
            throttleState.functions[funcNum] = newVal;
            updateFunctionButton(funcNum, newVal);
          }
        }
      }
    }
  } else if (data.type === 'POWER_CHANGED') {
    const connId = data.connectionId;
    const status = data.payload.status || 'UNKNOWN';
    connectionPowerStatus[connId] = status;
    addConsoleLine('power', `[${connId}] Power: ${status}`);
    updateConnectionPowerStatus(connId, status);
  } else if (data.type === 'COMMUNICATION_ERROR') {
    addConsoleLine('error', `[${data.connectionId}] Error: ${data.payload.message || 'Communication error'}`);
  } else if (data.type === 'CONNECTION_STATE_CHANGED') {
    addConsoleLine('power', `[${data.connectionId}] Connection: ${data.payload.connected ? 'Connected' : 'Disconnected'}`);
  }
}

function addConsoleLine(className, message) {
  const console = document.getElementById('consoleOutput');
  const line = document.createElement('div');
  line.className = 'console-line ' + className;
  const timestamp = new Date().toLocaleTimeString();
  line.innerHTML = `<span class="console-timestamp">${timestamp}</span>${message}`;
  console.appendChild(line);
  // Auto-scroll to bottom
  console.scrollTop = console.scrollHeight;
  // Limit to 500 lines to prevent memory issues
  while (console.children.length > 500) {
    console.removeChild(console.firstChild);
  }
}

function updateConnectionPowerStatus(connId, status) {
  // Update power status in connections list
  const connections = document.querySelectorAll('.connection-item');
  connections.forEach(item => {
    if (item.textContent.includes(connId)) {
      // Find and update power status in this connection item
      const powerElement = item.querySelector('.power-status');
      if (powerElement) {
        const powerIcon = status === 'ON' ? '🟢' : status === 'OFF' ? '🔴' : '🟡';
        powerElement.textContent = `Power: ${powerIcon} ${status}`;
      }
    }
  });
}

async function setControllerRole(connectionId, role, enabled) {
  try {
    const res = await fetch('/connections/setRole?connectionId=' + encodeURIComponent(connectionId) + '&role=' + role + '&enabled=' + enabled, { method: 'POST' });
    const data = await res.json();
    if (res.ok) {
      showStatus('Controller role updated', 'success');
      loadConnections();
    } else {
      showStatus('Error: ' + (data.error || 'Unknown error'), 'error');
      loadConnections(); // Reload to reset checkboxes
    }
  } catch (err) {
    showStatus('Error: ' + err.message, 'error');
    loadConnections(); // Reload to reset checkboxes
  }
}

async function discoverDevices() {
  const statusDiv = document.getElementById('discoverStatus');
  const devicesDiv = document.getElementById('discoveredDevices');
  statusDiv.textContent = 'Scanning for controllers...';
  devicesDiv.style.display = 'none';
  try {
    const res = await fetch('/api/discover');
    const data = await res.json();
    if (res.ok) {
      if (data.devices && data.devices.length > 0) {
        statusDiv.textContent = 'Found ' + data.devices.length + ' controller(s)';
        devicesDiv.innerHTML = '<div style="margin-top: 10px;"><strong>Detected Controllers:</strong></div>';
        data.devices.forEach(device => {
          const deviceDiv = document.createElement('div');
          deviceDiv.style.cssText = 'margin: 5px 0; padding: 8px; background: white; border-radius: 3px; border: 1px solid #ddd;';
          deviceDiv.innerHTML = '<strong>' + device.description + '</strong> (' + device.systemType + ')<br>' +
            '<small style="color: #666;">Port: ' + device.port + ' | VID:PID ' + device.vendorId + ':' + device.productId + '</small><br>' +
            '<button onclick="useDiscoveredDevice(\'' + device.port + '\', \'' + device.systemType + '\')" style="margin-top: 5px; padding: 5px 10px; background: #0e639c; color: white; border: none; border-radius: 3px; cursor: pointer; font-size: 12px;">Use This Device</button>';
          devicesDiv.appendChild(deviceDiv);
        });
        devicesDiv.style.display = 'block';
      } else {
        statusDiv.textContent = 'No controllers detected';
      }
    } else {
      statusDiv.textContent = 'Error: ' + (data.error || 'Unknown error');
    }
  } catch (err) {
    statusDiv.textContent = 'Error: ' + err.message;
  }
}

function useDiscoveredDevice(port, systemType) {
  document.getElementById('portName').value = port;
  document.getElementById('systemType').value = systemType;
  // Trigger change event to show/hide appropriate fields
  document.getElementById('systemType').dispatchEvent(new Event('change'));
  showStatus('Device selected: ' + port, 'success');
}

// Initialize on page load
loadSystems();
loadPorts();
loadConnections();
setInterval(loadConnections, 5000);
setInterval(loadPorts, 5000);
connectEventStream();

