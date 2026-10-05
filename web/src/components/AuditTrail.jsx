import React from 'react';

/**
 * AuditTrail.jsx - Tabela de Auditoria em Tempo Real
 * Exibe trilha cronológica de ações, comandos e eventos críticos com timestamps ISO-8601.
 */
export const AuditTrail = ({ logs }) => {
  return (
    <div className="audit-trail-container">
      <div className="audit-header">
        <h3>Trilha de Auditoria & Compliance (JSON Lines)</h3>
        <span className="audit-count">{logs.length} registros recentes</span>
      </div>

      <div className="table-responsive">
        <table className="audit-table">
          <thead>
            <tr>
              <th>Timestamp (ISO-8601 UTC)</th>
              <th>Dispositivo</th>
              <th>Tipo de Evento</th>
              <th>Detalhes / Ação</th>
            </tr>
          </thead>
          <tbody>
            {logs.length === 0 ? (
              <tr>
                <td colSpan="4" className="empty-table-cell">Nenhum registro de auditoria gravado ainda.</td>
              </tr>
            ) : (
              logs.map((log, index) => {
                const isCommand = log.event_type?.includes('COMMAND');
                const isConnect = log.event_type?.includes('CONNECT');

                return (
                  <tr key={index} className={isCommand ? 'row-command' : ''}>
                    <td className="font-mono text-muted">{log.timestamp}</td>
                    <td className="font-semibold">{log.device_id || 'SISTEMA'}</td>
                    <td>
                      <span className={`event-badge ${isCommand ? 'badge-command' : isConnect ? 'badge-connect' : 'badge-default'}`}>
                        {log.event_type}
                      </span>
                    </td>
                    <td className="font-mono text-xs">
                      {JSON.stringify(log.details || log.data || {})}
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default AuditTrail;
