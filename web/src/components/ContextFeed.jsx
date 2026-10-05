import React from 'react';

/**
 * ContextFeed.jsx - Feed Lateral de Telemetria de Contexto
 * Exibe eventos contextuais em tempo real como mudanças de foco de app, quedas de bateria, etc.
 */
export const ContextFeed = ({ events }) => {
  return (
    <div className="context-feed-container">
      <div className="context-feed-header">
        <h3>Feed de Contexto</h3>
        <span className="live-tag">TEMPO REAL</span>
      </div>

      <div className="feed-list">
        {events.length === 0 ? (
          <div className="empty-feed">Aguardando eventos contextuais...</div>
        ) : (
          events.map((evt, idx) => {
            const timeStr = new Date(evt.timestamp).toLocaleTimeString();
            const isWarning = evt.severity === 'warning';
            const isCritical = evt.severity === 'critical';

            return (
              <div 
                key={idx} 
                className={`feed-item ${isWarning ? 'item-warning' : ''} ${isCritical ? 'item-critical' : ''}`}
              >
                <div className="feed-item-top">
                  <span className="feed-device">{evt.deviceId}</span>
                  <span className="feed-time">{timeStr}</span>
                </div>
                <p className="feed-message">{evt.message}</p>
                {evt.details && (
                  <div className="feed-details-pill">
                    {evt.details}
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};

export default ContextFeed;
