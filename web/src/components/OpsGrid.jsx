import React, { useEffect, useState } from 'react';

/**
 * OpsGrid.jsx - Grid de Dispositivos com Fetch Local para Status
 * 
 * Funcionalidades:
 * 1. Fetch Local: Consulta periodicamente http://${serverIp}:5000/api/status para manter os cards
 *    atualizados sem a necessidade de manter WebSockets pesados abertos para cada celular.
 * 2. Seletor de IP do PC: Permite ao operador digitar o IP local da máquina (ex: 192.168.1.100).
 * 3. Lazy Trigger: Ao clicar em "Assistir Live", aciona o LiveMonitor que abre o WebSocket na hora.
 */
export const OpsGrid = ({ 
  serverIp, 
  onServerIpChange, 
  activeDeviceId, 
  onSelectDevice, 
  onSendCommand 
}) => {
  const [devices, setDevices] = useState([
    {
      device_id: 'VND1',
      room_name: 'device_VND1',
      salesperson_name: 'Carlos Eduardo (SP - Vendas B2B)',
      mode: 'IDLE',
      battery_pct: 88,
      is_charging: true,
      focused_package: 'com.whatsapp.w4b',
      cpu_usage_pct: 12.0,
      ram_usage_mb: 210,
      latency_ms: 14.0,
      is_online: true
    },
    {
      device_id: 'VND2',
      room_name: 'device_VND2',
      salesperson_name: 'Mariana Souza (RJ - Contas Enterprise)',
      mode: 'FOCUS',
      battery_pct: 65,
      is_charging: false,
      focused_package: 'com.salesforce.chatter',
      cpu_usage_pct: 18.5,
      ram_usage_mb: 195,
      latency_ms: 22.0,
      is_online: true
    },
    {
      device_id: 'VND3',
      room_name: 'device_VND3',
      salesperson_name: 'Roberto Lima (MG - Logística Campo)',
      mode: 'IDLE',
      battery_pct: 18,
      is_charging: false,
      focused_package: 'com.corp.kiosk',
      cpu_usage_pct: 4.2,
      ram_usage_mb: 98,
      latency_ms: 35.0,
      is_online: false
    }
  ]);

  const [inputIp, setInputIp] = useState(serverIp);
  const [lastSyncTime, setLastSyncTime] = useState('Recém carregado');

  useEffect(() => {
    setInputIp(serverIp);
  }, [serverIp]);

  // Fetch Local de Status: Polling leve a cada 3 segundos
  useEffect(() => {
    const fetchStatus = async () => {
      try {
        const response = await fetch(`http://${serverIp}:5000/api/status`, {
          method: 'GET',
          headers: { 'Accept': 'application/json' }
        });
        if (response.ok) {
          const data = await response.json();
          if (data.devices && data.devices.length > 0) {
            setDevices(data.devices);
            setLastSyncTime(new Date().toLocaleTimeString());
          }
        }
      } catch (_) {
        // Fallback silencioso para mock local caso o PC esteja temporariamente offline
      }
    };

    fetchStatus();
    const interval = setInterval(fetchStatus, 3000);
    return () => clearInterval(interval);
  }, [serverIp]);

  const handleIpSubmit = (e) => {
    e.preventDefault();
    if (inputIp.trim()) {
      onServerIpChange(inputIp.trim());
    }
  };

  return (
    <div className="ops-grid-container">
      {/* Header com Seletor de IP do PC Local */}
      <div className="ops-grid-header">
        <div>
          <h2>Dispositivos Wi-Fi ({devices.length})</h2>
          <span className="grid-subtitle">Sync Local: {lastSyncTime}</span>
        </div>

        {/* Campo de Configuração do IP do PC */}
        <form onSubmit={handleIpSubmit} className="ip-config-form">
          <input 
            type="text"
            value={inputIp}
            onChange={(e) => setInputIp(e.target.value)}
            placeholder="IP do PC (ex: 192.168.1.100)"
            className="ip-input"
          />
          <button type="submit" className="ip-save-btn">Salvar</button>
        </form>
      </div>

      {/* Grid de Cards dos Dispositivos */}
      <div className="device-cards-grid">
        {devices.map((device) => {
          const isSelected = activeDeviceId === device.device_id;
          const isLive = device.mode === 'LIVE';
          const isFocus = device.mode === 'FOCUS';
          const isOnline = device.is_online;

          return (
            <div 
              key={device.device_id}
              className={`device-card ${isSelected ? 'selected' : ''} ${!isOnline ? 'offline' : ''}`}
              onClick={() => onSelectDevice(device.device_id)}
            >
              {/* Topo do Card */}
              <div className="card-top">
                <div className="device-badge-row">
                  <span className={`status-pill ${isOnline ? (isLive ? 'live' : isFocus ? 'focus' : 'idle') : 'offline'}`}>
                    {isOnline ? (isLive ? '🔴 LIVE' : isFocus ? '⚡ FOCO' : '🟢 IDLE') : '⚪ OFFLINE'}
                  </span>
                  <span className="device-id-tag">device_{device.device_id}</span>
                </div>

                <div className="health-check-pill" title="Latência Wi-Fi Direct-to-Local">
                  <span className="dot" />
                  <span>{device.latency_ms > 0 ? `${Math.round(device.latency_ms)}ms` : '< 20ms'}</span>
                </div>
              </div>

              {/* Informações do Vendedor */}
              <div className="salesperson-section">
                <span className="salesperson-label">Colaborador / Terminal:</span>
                <p className="salesperson-name">{device.salesperson_name}</p>
              </div>

              {/* Bateria & App em Foco */}
              <div className="card-metrics-grid">
                <div className="metric-box">
                  <span className="metric-title">Bateria</span>
                  <div className="battery-display">
                    <div className="battery-bar-outer">
                      <div 
                        className={`battery-bar-inner ${device.battery_pct <= 20 ? 'low' : ''}`}
                        style={{ width: `${device.battery_pct}%` }}
                      />
                    </div>
                    <span className="battery-text">
                      {device.battery_pct}% {device.is_charging ? '⚡' : ''}
                    </span>
                  </div>
                </div>

                <div className="metric-box">
                  <span className="metric-title">App Ativo</span>
                  <span className="app-focus-text">
                    {device.focused_package?.split('.').pop() || 'kiosk'}
                  </span>
                </div>
              </div>

              {/* Ações (Lazy WebSocket Trigger) */}
              <div className="card-actions-row" onClick={(e) => e.stopPropagation()}>
                <button 
                  className={`btn-stream ${isSelected ? 'btn-live-active' : ''}`}
                  onClick={() => {
                    onSelectDevice(device.device_id);
                    onSendCommand(device.device_id, 'SET_MODE', { mode: isLive ? 'IDLE' : 'LIVE' });
                  }}
                >
                  {isSelected ? '📺 Visualizando Live' : '▶️ Assistir Live (Lazy WS)'}
                </button>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default OpsGrid;
