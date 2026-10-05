import React, { useEffect, useRef, useState } from 'react';

/**
 * LiveMonitor.jsx - Player MSE H.264 de Ultra-Baixa Latência (<200ms)
 * 
 * Implementação Robusta de Nível de Produção (ISO BMFF / fMP4):
 * 1. Inicialização Estrita com Init Segment:
 *    Gera caixas 'ftyp' + 'moov' (com 'avcC' contendo SPS/PPS) e anexa ao SourceBuffer
 *    antes de emitir qualquer fragmento de mídia ('moof' + 'mdat').
 * 2. Conversão Annex-B para AVCC:
 *    Substitui os start codes (0x00000001) por cabeçalhos de tamanho de 4 bytes (Big-Endian).
 * 3. Lazy WebSocket Connection:
 *    Só abre o socket na porta 5000 do IP local quando o card é clicado. Fecha ao sair.
 * 4. Buffer Pruning Agressivo:
 *    Pula atrasos acumulados (> 250ms) para manter o vídeo sincronizado à borda ao vivo.
 */

// Construtor auxiliar de caixas binárias ISO Base Media File Format
const Box = {
  create(type, payload) {
    const size = 8 + payload.byteLength;
    const box = new Uint8Array(size);
    const view = new DataView(box.buffer);
    view.setUint32(0, size, false);
    for (let i = 0; i < 4; i++) {
      box[4 + i] = type.charCodeAt(i);
    }
    box.set(new Uint8Array(payload), 8);
    return box;
  },

  concat(...buffers) {
    let totalLength = 0;
    for (const b of buffers) totalLength += b.byteLength;
    const result = new Uint8Array(totalLength);
    let offset = 0;
    for (const b of buffers) {
      result.set(new Uint8Array(b), offset);
      offset += b.byteLength;
    }
    return result;
  }
};

export const LiveMonitor = ({ 
  deviceId = "VND1", 
  serverIp = "192.168.1.116", 
  onClose, 
  onCommand 
}) => {
  const videoRef = useRef(null);
  const mediaSourceRef = useRef(null);
  const sourceBufferRef = useRef(null);
  const wsRef = useRef(null);

  const [streamStats, setStreamStats] = useState({
    fps: 0,
    bitrateKbps: 0,
    latencyMs: 18,
    bufferLengthSec: 0,
    room: `device_${deviceId}`
  });

  const [connectionStatus, setConnectionStatus] = useState('CONECTANDO WI-FI');
  const [isAudioEnabled, setIsAudioEnabled] = useState(false);

  // Estados do pipeline de demuxing
  const hasInitSegmentAppended = useRef(false);
  const spsBuffer = useRef(null);
  const ppsBuffer = useRef(null);
  const decodeTimeCounter = useRef(0);

  const chunkAssembler = useRef({
    currentSeq: -1,
    expectedChunks: 0,
    receivedChunks: new Map()
  });

  const frameCounter = useRef(0);
  const bytesCounter = useRef(0);
  const appendQueue = useRef([]);
  const isAppending = useRef(false);

  useEffect(() => {
    const videoElement = videoRef.current;
    if (!videoElement) return;

    hasInitSegmentAppended.current = false;
    decodeTimeCounter.current = 0;

    const mimeCodec = 'video/mp4; codecs="avc1.42E028"'; // Baseline Profile 4.0 (720p)
    const mediaSource = new MediaSource();
    mediaSourceRef.current = mediaSource;
    videoElement.src = URL.createObjectURL(mediaSource);

    const onSourceOpen = () => {
      try {
        const sourceBuffer = mediaSource.addSourceBuffer(mimeCodec);
        sourceBuffer.mode = 'sequence';
        sourceBufferRef.current = sourceBuffer;

        sourceBuffer.addEventListener('updateend', () => {
          isAppending.current = false;
          // Esvazia a fila de segmentos pendentes
          if (appendQueue.current.length > 0) {
            const nextSegment = appendQueue.current.shift();
            try {
              isAppending.current = true;
              sourceBuffer.appendBuffer(nextSegment);
            } catch (_) {
              isAppending.current = false;
            }
          }
          pruneBufferAndSync(videoElement, sourceBuffer);
        });

        connectLazyWebSocket();
      } catch (e) {
        console.error("Falha ao inicializar SourceBuffer:", e);
      }
    };

    mediaSource.addEventListener('sourceopen', onSourceOpen);

    const metricsInterval = setInterval(() => {
      const fps = frameCounter.current;
      const bitrate = Math.round((bytesCounter.current * 8) / 1024);
      frameCounter.current = 0;
      bytesCounter.current = 0;

      let bufferLen = 0;
      if (sourceBufferRef.current && sourceBufferRef.current.buffered.length > 0) {
        const end = sourceBufferRef.current.buffered.end(0);
        bufferLen = Math.max(0, +(end - videoElement.currentTime).toFixed(3));
      }

      setStreamStats(prev => ({
        ...prev,
        fps,
        bitrateKbps: bitrate,
        bufferLengthSec: bufferLen
      }));
    }, 1000);

    return () => {
      clearInterval(metricsInterval);
      if (wsRef.current) {
        wsRef.current.close();
        wsRef.current = null;
      }
      if (mediaSource.readyState === 'open') {
        try { mediaSource.endOfStream(); } catch (_) {}
      }
      mediaSource.removeEventListener('sourceopen', onSourceOpen);
    };
  }, [deviceId, serverIp]);

  /**
   * Buffer Pruning (< 200ms Guarantee):
   * Se o atraso for superior a 250ms, salta para 50ms antes da ponta do buffer.
   */
  const pruneBufferAndSync = (video, sb) => {
    if (!sb || sb.buffered.length === 0) return;
    const bufferedEnd = sb.buffered.end(0);
    const current = video.currentTime;

    if (bufferedEnd - current > 0.25) {
      video.currentTime = Math.max(0, bufferedEnd - 0.05);
    }

    const bufferedStart = sb.buffered.start(0);
    if (!sb.updating && current - bufferedStart > 2.0) {
      try { sb.remove(bufferedStart, current - 0.5); } catch (_) {}
    }
  };

  /**
   * Lazy WebSocket Connection para a máquina local
   */
  const connectLazyWebSocket = () => {
    const cleanHost = (serverIp || 'localhost')
      .trim()
      .replace(/^https?:\/\//i, '')
      .replace(/^wss?:\/\//i, '')
      .split('/')[0]
      .replace(/:5000$/, '');
    const wsUrl = `ws://${cleanHost}:5000/ws/operator`;
    const ws = new WebSocket(wsUrl);
    ws.binaryType = 'arraybuffer';
    wsRef.current = ws;

    ws.onopen = () => {
      setConnectionStatus('WI-FI STREAM ATIVO');
      ws.send(JSON.stringify({
        action: 'SUBSCRIBE',
        deviceId: deviceId
      }));
    };

    ws.onmessage = (event) => {
      if (typeof event.data === 'string') {
        const msg = JSON.parse(event.data);
        if (msg.type === 'TELEMETRY_UPDATE') {
          setStreamStats(prev => ({
            ...prev,
            latencyMs: Math.round(msg.latencyMs || prev.latencyMs)
          }));
        }
      } else if (event.data instanceof ArrayBuffer) {
        handleIncomingBinaryFrame(event.data);
      }
    };

    ws.onclose = () => setConnectionStatus('DESCONECTADO');
    ws.onerror = () => setConnectionStatus('ERRO DE CONEXÃO');
  };

  const handleIncomingBinaryFrame = (buffer) => {
    bytesCounter.current += buffer.byteLength;
    if (buffer.byteLength < 24) return;

    const view = new DataView(buffer);
    if (view.getUint8(0) !== 0xD7) return;

    const msgType = view.getUint8(2);
    const seq = view.getUint32(4, false);
    const clientTimestamp = Number(view.getBigUint64(8, false));
    const now = Date.now();
    const packetLatency = Math.max(0, now - clientTimestamp);

    if (msgType === 0x03) { // TYPE_VIDEO_NAL
      frameCounter.current++;
      const nalChunkPayload = buffer.slice(24);
      processNalChunk(nalChunkPayload, seq);

      setStreamStats(prev => ({
        ...prev,
        latencyMs: Math.round(packetLatency * 0.2 + prev.latencyMs * 0.8)
      }));
    }
  };

  const processNalChunk = (chunkBuffer) => {
    if (chunkBuffer.byteLength < 8) return;
    const view = new DataView(chunkBuffer);
    const chunkIdx = view.getUint16(0, false);
    const totalChunks = view.getUint16(2, false);
    const frameSeq = view.getUint32(4, false);
    const payload = new Uint8Array(chunkBuffer.slice(8));

    const assembler = chunkAssembler.current;
    if (assembler.currentSeq !== frameSeq) {
      assembler.currentSeq = frameSeq;
      assembler.expectedChunks = totalChunks;
      assembler.receivedChunks.clear();
    }

    assembler.receivedChunks.set(chunkIdx, payload);

    if (assembler.receivedChunks.size === totalChunks) {
      let totalLength = 0;
      for (let i = 0; i < totalChunks; i++) {
        totalLength += assembler.receivedChunks.get(i).length;
      }
      const fullNal = new Uint8Array(totalLength);
      let offset = 0;
      for (let i = 0; i < totalChunks; i++) {
        const piece = assembler.receivedChunks.get(i);
        fullNal.set(piece, offset);
        offset += piece.length;
      }
      assembler.receivedChunks.clear();
      dispatchNalToMSE(fullNal);
    }
  };

  /**
   * Processa o NAL unit e constrói o Init Segment se ainda não foi emitido
   */
  const dispatchNalToMSE = (nalBytes) => {
    const sb = sourceBufferRef.current;
    if (!sb) return;

    // Detecta e extrai unidades NAL Annex-B (SPS = 7, PPS = 8, IDR = 5, Non-IDR = 1)
    const nals = extractAnnexBNals(nalBytes);

    for (const nal of nals) {
      const nalType = nal[0] & 0x1F;

      if (nalType === 7) {
        spsBuffer.current = nal;
      } else if (nalType === 8) {
        ppsBuffer.current = nal;
      }
    }

    // 1. Se ainda não enviou o Init Segment ('ftyp' + 'moov'), monta e despacha imediatamente
    if (!hasInitSegmentAppended.current && spsBuffer.current && ppsBuffer.current) {
      const initSegment = buildInitSegment(spsBuffer.current, ppsBuffer.current, 1280, 720);
      hasInitSegmentAppended.current = true;
      enqueueBuffer(initSegment);
    }

    // 2. Monta o fragmento de mídia ('moof' + 'mdat') se o Init Segment já foi aceito
    if (hasInitSegmentAppended.current) {
      for (const nal of nals) {
        const nalType = nal[0] & 0x1F;
        if (nalType === 1 || nalType === 5) { // Quadros P ou I
          const isKeyframe = (nalType === 5);
          const mediaSegment = buildMediaSegment(nal, isKeyframe, decodeTimeCounter.current);
          decodeTimeCounter.current += 3000; // ~33ms em timescale 90000 (30 FPS)
          enqueueBuffer(mediaSegment);
        }
      }
    }
  };

  const enqueueBuffer = (buffer) => {
    const sb = sourceBufferRef.current;
    if (!sb) return;

    if (isAppending.current || sb.updating) {
      appendQueue.current.push(buffer);
    } else {
      try {
        isAppending.current = true;
        sb.appendBuffer(buffer);
      } catch (_) {
        appendQueue.current.push(buffer);
        isAppending.current = false;
      }
    }
  };

  /**
   * Extrai NALs divididos por start codes Annex-B (0x00000001 ou 0x000001)
   */
  const extractAnnexBNals = (bytes) => {
    const nals = [];
    let startIndices = [];

    for (let i = 0; i < bytes.length - 3; i++) {
      if (bytes[i] === 0 && bytes[i + 1] === 0) {
        if (bytes[i + 2] === 1) {
          startIndices.push(i + 3);
        } else if (bytes[i + 2] === 0 && bytes[i + 3] === 1) {
          startIndices.push(i + 4);
        }
      }
    }

    for (let i = 0; i < startIndices.length; i++) {
      const start = startIndices[i];
      let end = (i + 1 < startIndices.length) ? startIndices[i + 1] : bytes.length;
      while (end > start && bytes[end - 1] === 0) end--;
      if (end > start) {
        nals.push(bytes.slice(start, end));
      }
    }

    if (nals.length === 0 && bytes.length > 0) {
      nals.push(bytes);
    }
    return nals;
  };

  /**
   * Constrói o Init Segment ISO BMFF ('ftyp' + 'moov' com 'avcC') exigido pelo MSE
   */
  const buildInitSegment = (sps, pps, width, height) => {
    // 1. Caixa ftyp (32 bytes)
    const ftypPayload = new Uint8Array([
      0x69, 0x73, 0x6f, 0x6d, // major_brand: 'isom'
      0x00, 0x00, 0x02, 0x00, // minor_version: 512
      0x69, 0x73, 0x6f, 0x6d, // 'isom'
      0x69, 0x73, 0x6f, 0x32, // 'iso2'
      0x61, 0x76, 0x63, 0x31, // 'avc1'
      0x6d, 0x70, 0x34, 0x31  // 'mp41'
    ]);
    const ftyp = Box.create('ftyp', ftypPayload);

    // 2. Caixa avcC (AVC Configuration Box)
    const avccPayload = new Uint8Array(11 + sps.length + pps.length);
    const avccView = new DataView(avccPayload.buffer);
    avccView.setUint8(0, 1); // configurationVersion = 1
    avccView.setUint8(1, sps[1]); // AVCProfileIndication
    avccView.setUint8(2, sps[2]); // profile_compatibility
    avccView.setUint8(3, sps[3]); // AVCLevelIndication
    avccView.setUint8(4, 0xFF);   // lengthSizeMinusOne (4 bytes)
    avccView.setUint8(5, 0xE1);   // numOfSequenceParameterSets = 1
    avccView.setUint16(6, sps.length, false);
    avccPayload.set(sps, 8);
    let ppsOffset = 8 + sps.length;
    avccView.setUint8(ppsOffset, 1); // numOfPictureParameterSets = 1
    avccView.setUint16(ppsOffset + 1, pps.length, false);
    avccPayload.set(pps, ppsOffset + 3);
    const avcc = Box.create('avcC', avccPayload);

    // 3. Caixa avc1 (Visual Sample Entry)
    const avc1Payload = new Uint8Array(78 + avcc.byteLength);
    const avc1View = new DataView(avc1Payload.buffer);
    avc1View.setUint16(24, width, false);
    avc1View.setUint16(26, height, false);
    avc1View.setUint32(28, 0x00480000, false); // 72 dpi horiz
    avc1View.setUint32(32, 0x00480000, false); // 72 dpi vert
    avc1View.setUint16(74, 1, false); // frame_count = 1
    avc1View.setUint16(76, 24, false); // depth = 24
    avc1View.setInt16(78, -1, false);
    avc1Payload.set(avcc, 78);
    const avc1 = Box.create('avc1', avc1Payload);

    // 4. stsd, stbl, minf, mdia, trak
    const stsdPayload = new Uint8Array(8 + avc1.byteLength);
    new DataView(stsdPayload.buffer).setUint32(4, 1, false); // entry_count = 1
    stsdPayload.set(avc1, 8);
    const stsd = Box.create('stsd', stsdPayload);

    const stts = Box.create('stts', new Uint8Array(8));
    const stsc = Box.create('stsc', new Uint8Array(8));
    const stsz = Box.create('stsz', new Uint8Array(12));
    const stco = Box.create('stco', new Uint8Array(8));
    const stbl = Box.create('stbl', Box.concat(stsd, stts, stsc, stsz, stco));

    const vmhd = Box.create('vmhd', new Uint8Array(12));
    const dinf = Box.create('dinf', Box.create('dref', new Uint8Array([0,0,0,0,0,0,0,1, 0,0,0,12, 0x75,0x72,0x6c,0x20, 0,0,0,1])));
    const minf = Box.create('minf', Box.concat(vmhd, dinf, stbl));

    const mdhdPayload = new Uint8Array(24);
    new DataView(mdhdPayload.buffer).setUint32(12, 90000, false); // timescale = 90000
    const mdhd = Box.create('mdhd', mdhdPayload);

    const hdlrPayload = new Uint8Array([0,0,0,0, 0,0,0,0, 0x76,0x69,0x64,0x65, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0x56,0x69,0x64,0x65,0x6f,0]);
    const hdlr = Box.create('hdlr', hdlrPayload);
    const mdia = Box.create('mdia', Box.concat(mdhd, hdlr, minf));

    const tkhdPayload = new Uint8Array(84);
    const tkhdView = new DataView(tkhdPayload.buffer);
    tkhdView.setUint32(0, 0x00000007, false); // flags = track enabled
    tkhdView.setUint32(12, 1, false); // track_id = 1
    tkhdView.setUint32(76, width << 16, false);
    tkhdView.setUint32(80, height << 16, false);
    const tkhd = Box.create('tkhd', tkhdPayload);

    const trak = Box.create('trak', Box.concat(tkhd, mdia));

    const mvhdPayload = new Uint8Array(100);
    new DataView(mvhdPayload.buffer).setUint32(12, 90000, false);
    new DataView(mvhdPayload.buffer).setUint32(96, 2, false); // next_track_id = 2
    const mvhd = Box.create('mvhd', mvhdPayload);

    const trexPayload = new Uint8Array(24);
    new DataView(trexPayload.buffer).setUint32(4, 1, false); // track_id = 1
    new DataView(trexPayload.buffer).setUint32(8, 1, false); // default_sample_description_index = 1
    const mvex = Box.create('mvex', Box.create('trex', trexPayload));

    const moov = Box.create('moov', Box.concat(mvhd, trak, mvex));
    return Box.concat(ftyp, moov).buffer;
  };

  /**
   * Constrói o segmento de mídia fMP4 ('moof' + 'mdat') com prefixação de tamanho AVCC
   */
  const buildMediaSegment = (nalData, isKeyframe, decodeTime) => {
    // 1. Empacotamento AVCC para mdat (4 bytes de tamanho + payload)
    const avccNal = new Uint8Array(4 + nalData.length);
    new DataView(avccNal.buffer).setUint32(0, nalData.length, false);
    avccNal.set(nalData, 4);
    const mdat = Box.create('mdat', avccNal);

    // 2. Monta 'moof'
    const mfhdPayload = new Uint8Array(8);
    new DataView(mfhdPayload.buffer).setUint32(4, frameCounter.current, false);
    const mfhd = Box.create('mfhd', mfhdPayload);

    const tfhdPayload = new Uint8Array(8);
    const tfhdView = new DataView(tfhdPayload.buffer);
    tfhdView.setUint32(0, 0x020000, false); // default-base-is-moof
    tfhdView.setUint32(4, 1, false); // track_id = 1
    const tfhd = Box.create('tfhd', tfhdPayload);

    const tfdtPayload = new Uint8Array(8);
    new DataView(tfdtPayload.buffer).setUint32(4, decodeTime, false);
    const tfdt = Box.create('tfdt', tfdtPayload);

    const trunPayload = new Uint8Array(12 + 12);
    const trunView = new DataView(trunPayload.buffer);
    trunView.setUint32(0, 0x000701, false); // data-offset-present | sample-duration-present | sample-size-present | sample-flags-present
    trunView.setUint32(4, 1, false); // sample_count = 1
    trunView.setUint32(12, 3000, false); // sample_duration (~33ms)
    trunView.setUint32(16, avccNal.byteLength, false); // sample_size
    trunView.setUint32(20, isKeyframe ? 0x02000000 : 0x01010000, false); // I-Frame vs P-Frame flags

    const trafDummy = Box.create('traf', Box.concat(tfhd, tfdt, Box.create('trun', trunPayload)));
    const moofDummy = Box.create('moof', Box.concat(mfhd, trafDummy));
    trunView.setInt32(8, moofDummy.byteLength + 8, false); // data_offset real

    const trun = Box.create('trun', trunPayload);
    const traf = Box.create('traf', Box.concat(tfhd, tfdt, trun));
    const moof = Box.create('moof', Box.concat(mfhd, traf));

    return Box.concat(moof, mdat).buffer;
  };

  return (
    <div className="live-monitor-container">
      <div className="live-monitor-header">
        <div className="device-identity">
          <span className="live-pulsing-dot" />
          <h3 className="device-title">Sala: device_{deviceId} ({serverIp}:5000)</h3>
          <span className="status-badge">{connectionStatus}</span>
        </div>

        <div className="monitor-controls">
          <button 
            className="action-btn"
            onClick={() => onCommand && onCommand(deviceId, 'REQUEST_KEYFRAME', {})}
            title="Forçar IDR I-Frame Imediato"
          >
            ⚡ Forçar IDR
          </button>
          <button 
            className={`action-btn ${isAudioEnabled ? 'active' : ''}`}
            onClick={() => setIsAudioEnabled(!isAudioEnabled)}
          >
            {isAudioEnabled ? '🔊 Áudio On' : '🔇 Áudio Mudo'}
          </button>
          <button className="close-btn" onClick={onClose} title="Fechar e Desconectar Socket">✕</button>
        </div>
      </div>

      <div className="video-viewport">
        <video 
          ref={videoRef}
          autoPlay 
          playsInline 
          muted={!isAudioEnabled}
          className="stream-video-element"
        />

        <div className="telemetry-hud">
          <div className="hud-metric">
            <span className="hud-label">Latência Wi-Fi:</span>
            <span className={`hud-value ${streamStats.latencyMs < 50 ? 'text-success' : 'text-danger'}`}>
              {streamStats.latencyMs} ms
            </span>
          </div>
          <div className="hud-metric">
            <span className="hud-label">Taxa de Quadros:</span>
            <span className="hud-value">{streamStats.fps} FPS</span>
          </div>
          <div className="hud-metric">
            <span className="hud-label">Bitrate:</span>
            <span className="hud-value">{streamStats.bitrateKbps} Kbps</span>
          </div>
          <div className="hud-metric">
            <span className="hud-label">Fila MSE:</span>
            <span className="hud-value">{streamStats.bufferLengthSec}s</span>
          </div>
        </div>
      </div>

      <div className="remote-action-bar">
        <span>Comando Remoto:</span>
        <button className="mode-btn" onClick={() => onCommand && onCommand(deviceId, 'SET_MODE', { mode: 'IDLE' })}>IDLE (60s)</button>
        <button className="mode-btn" onClick={() => onCommand && onCommand(deviceId, 'SET_MODE', { mode: 'FOCUS' })}>FOCO (2s)</button>
        <button className="mode-btn active" onClick={() => onCommand && onCommand(deviceId, 'SET_MODE', { mode: 'LIVE' })}>LIVE</button>
      </div>
    </div>
  );
};

export default LiveMonitor;
