import React, { useState, useEffect } from 'react';
import OpsGrid from './components/OpsGrid';
import LiveMonitor from './components/LiveMonitor';
import ContextFeed from './components/ContextFeed';
import AuditTrail from './components/AuditTrail';
import './index.css';

const sanitizeIp = (ip) => {
  if (!ip) return '192.168.1.116';
  return ip
    .trim()
    .replace(/^https?:\/\//i, '')
    .replace(/^wss?:\/\//i, '')
    .split('/')[0]
    .replace(/:5000$/, '');
};

export function App() {
  const [serverIp, setServerIp] = useState(() => {
    const saved = localStorage.getItem('dt_server_ip');
    return sanitizeIp(saved || '192.168.1.116');
  });

  const [activeDeviceId, setActiveDeviceId] = useState('VND1');
  const [contextEvents, setContextEvents] = useState([
    {
      deviceId: 'VND1',
      timestamp: Date.now() - 12000,
      message: 'App em foco alternado para WhatsApp Business',
      details: 'Pacote: com.whatsapp.w4b',
      severity: 'normal'
    },
    {
      deviceId: 'VND3',
      timestamp: Date.now() - 45000,
      message: 'Bateria em nível crítico: 18%',
      details: 'Modo Offline Buffering preventivo',
      severity: 'warning'
    }
  ]);

  const [auditLogs, setAuditLogs] = useState([]);

  const handleServerIpChange = (newIp) => {
    const cleaned = sanitizeIp(newIp);
    setServerIp(cleaned);
    localStorage.setItem('dt_server_ip', cleaned);
  };

  // Carrega trilha de auditoria local
  useEffect(() => {
    const fetchAudit = async () => {
      try {
        const res = await fetch(`http://${serverIp}:5000/api/audit-logs?limit=30`);
        if (res.ok) {
          const data = await res.json();
          if (data.logs) setAuditLogs(data.logs);
        }
      } catch (_) {}
    };

    fetchAudit();
    const interval = setInterval(fetchAudit, 5000);
    return () => clearInterval(interval);
  }, [serverIp]);

  const handleSendCommand = async (deviceId, command, params) => {
    try {
      await fetch(`http://${serverIp}:5000/api/device/${deviceId}/command`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ command, ...params })
      });
    } catch (e) {
      console.warn("Falha ao despachar comando para o PC local:", e);
    }
  };

  return (
    <div className="digital-twin-dashboard">
      <header className="dashboard-navbar">
        <div className="navbar-brand">
          <span className="brand-icon">🛡️</span>
          <div className="brand-text">
            <h1>DIGITAL TWIN OPS CENTER</h1>
            <span className="brand-subtitle">Rede Wi-Fi Local (Direct-to-Local) | Frontend Vercel</span>
          </div>
        </div>

        <div className="navbar-stats">
          <div className="stat-pill">
            <span className="stat-dot green" />
            <span>PC Gateway: <strong>{serverIp}:5000</strong></span>
          </div>
          <div className="stat-pill">
            <span>Protocolo: <strong>Wire Binary (VND Tag)</strong></span>
          </div>
        </div>
      </header>

      <main className="dashboard-layout">
        <section className="layout-left">
          <OpsGrid 
            serverIp={serverIp}
            onServerIpChange={handleServerIpChange}
            activeDeviceId={activeDeviceId} 
            onSelectDevice={(id) => setActiveDeviceId(id)}
            onSendCommand={handleSendCommand}
          />
        </section>

        <section className="layout-center">
          {activeDeviceId ? (
            <LiveMonitor 
              deviceId={activeDeviceId}
              serverIp={serverIp}
              onClose={() => setActiveDeviceId(null)}
              onCommand={handleSendCommand}
            />
          ) : (
            <div className="no-stream-placeholder">
              <span className="placeholder-icon">📺</span>
              <h3>Nenhum dispositivo selecionado</h3>
              <p>Clique em "Assistir Live" em um dos vendedores para abrir a conexão direta via Wi-Fi.</p>
            </div>
          )}

          <AuditTrail logs={auditLogs} />
        </section>

        <section className="layout-right">
          <ContextFeed events={contextEvents} />
        </section>
      </main>
    </div>
  );
}

export default App;
