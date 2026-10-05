"""
Nó Central de Ingestão e Fan-Out de Baixa Latência (Wi-Fi Local / Direct-to-Local)
Host: 0.0.0.0:5000 (Acessível na rede local 192.168.x.x)

Características:
1. Room-Based Fan-Out:
   - Sala padrão por vendedor: 'device_VND1', 'device_VND2', etc.
   - Quando o operador no React clica em um vendedor, o navegador se inscreve na sala.
   - Frames binários recebidos de 'VND1' são repassados instantaneamente para a sala correspondente.
2. Endpoint GET /api/status para consumo leve e direto pelo OpsGrid no React.
3. Auditoria assíncrona desacoplada em audit.log (JSON Lines).
"""

import os
import sys
import time
import json
import struct
import logging
import threading
from queue import Queue, Empty
from datetime import datetime, timezone
from dataclasses import dataclass, field, asdict
from typing import Dict, Set, Optional, Any

from flask import Flask, request, jsonify
from flask_cors import CORS
from flask_sock import Sock

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)]
)
logger = logging.getLogger("LocalGateway")

app = Flask(__name__)
# CORS irrestrito e suporte a Private Network Access (PNA) para navegadores no Vercel
CORS(app, resources={r"/*": {"origins": "*"}})
sock = Sock(app)

@app.before_request
def handle_preflight():
    if request.method == "OPTIONS":
        res = app.make_default_options_response()
        res.headers["Access-Control-Allow-Origin"] = "*"
        res.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS, PUT, DELETE"
        res.headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization, X-Requested-With, X-Device-Tag, X-Protocol-Version"
        res.headers["Access-Control-Allow-Private-Network"] = "true"
        return res

@app.after_request
def add_cors_pna_headers(response):
    response.headers["Access-Control-Allow-Origin"] = "*"
    response.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS, PUT, DELETE"
    response.headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization, X-Requested-With, X-Device-Tag, X-Protocol-Version"
    response.headers["Access-Control-Allow-Private-Network"] = "true"
    return response

MAGIC_BYTE = 0xD7
PROTOCOL_VERSION = 0x01
HEADER_SIZE = 20
DEVICE_TAG_SIZE = 4

TYPE_HELLO = 0x01
TYPE_TELEMETRY = 0x02
TYPE_VIDEO_NAL = 0x03
TYPE_AUDIO = 0x04
TYPE_COMMAND = 0x05
TYPE_HEARTBEAT = 0x07

AUDIT_LOG_FILE = os.path.join(os.path.dirname(__file__), "audit.log")

@dataclass
class DeviceState:
    device_id: str
    room_name: str
    salesperson_name: str = "Vendedor Local"
    mode: str = "IDLE"
    battery_pct: int = 100
    is_charging: bool = False
    focused_package: str = "com.corp.kiosk"
    cpu_usage_pct: float = 0.0
    ram_usage_mb: int = 0
    latency_ms: float = 0.0
    last_seq: int = 0
    is_online: bool = True
    last_seen: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())

class RoomManager:
    def __init__(self):
        self._lock = threading.RLock()
        self._devices: Dict[str, DeviceState] = {}
        self._device_sockets: Dict[str, Any] = {}
        # Mapeamento de Salas: "device_VND1" -> Set[operator_ws]
        self._rooms: Dict[str, Set[Any]] = {}

        self._salesperson_catalog = {
            "VND1": "Carlos Eduardo (SP - Vendas B2B)",
            "VND2": "Mariana Souza (RJ - Contas Enterprise)",
            "VND3": "Roberto Lima (MG - Logística Campo)"
        }

    def register_device(self, device_tag: str, ws: Any = None) -> DeviceState:
        with self._lock:
            tag = device_tag.strip().upper()
            room_name = f"device_{tag}"
            now_iso = datetime.now(timezone.utc).isoformat()

            if tag not in self._devices:
                salesperson = self._salesperson_catalog.get(tag, f"Vendedor {tag}")
                self._devices[tag] = DeviceState(
                    device_id=tag,
                    room_name=room_name,
                    salesperson_name=salesperson,
                    is_online=True,
                    last_seen=now_iso
                )
            else:
                self._devices[tag].is_online = True
                self._devices[tag].last_seen = now_iso

            if ws is not None:
                self._device_sockets[tag] = ws

            if room_name not in self._rooms:
                self._rooms[room_name] = set()

            return self._devices[tag]

    def set_offline(self, device_tag: str):
        with self._lock:
            tag = device_tag.strip().upper()
            if tag in self._devices:
                self._devices[tag].is_online = False
                self._devices[tag].last_seen = datetime.now(timezone.utc).isoformat()
            self._device_sockets.pop(tag, None)

    def subscribe_operator(self, room_name: str, operator_ws: Any):
        with self._lock:
            if room_name not in self._rooms:
                self._rooms[room_name] = set()
            self._rooms[room_name].add(operator_ws)
            logger.info(f"Operador ingressou na sala [{room_name}]. Inscritos: {len(self._rooms[room_name])}")

    def unsubscribe_operator(self, operator_ws: Any):
        with self._lock:
            for room, subs in self._rooms.items():
                subs.discard(operator_ws)

    def get_room_subscribers(self, room_name: str) -> list:
        with self._lock:
            return list(self._rooms.get(room_name, []))

    def get_device_ws(self, device_tag: str) -> Optional[Any]:
        with self._lock:
            return self._device_sockets.get(device_tag.strip().upper())

    def update_telemetry(self, device_tag: str, mode: str, battery: int, charging: bool,
                         pkg: str, cpu: float, ram: int, seq: int, latency: float):
        with self._lock:
            dev = self.register_device(device_tag)
            dev.mode = mode
            dev.battery_pct = battery
            dev.is_charging = charging
            dev.focused_package = pkg
            dev.cpu_usage_pct = cpu
            dev.ram_usage_mb = ram
            dev.last_seq = seq
            dev.latency_ms = latency
            dev.last_seen = datetime.now(timezone.utc).isoformat()

    def get_status_summary(self) -> list:
        with self._lock:
            return [asdict(dev) for dev in self._devices.values()]

rooms = RoomManager()

# --- AUDITORIA ASSÍNCRONA ---

class AsyncAuditLogger:
    def __init__(self, log_path: str):
        self.log_path = log_path
        self.queue = Queue(maxsize=10000)
        self.running = True
        self.worker = threading.Thread(target=self._worker, name="AuditWorker", daemon=True)
        self.worker.start()

    def log(self, event_type: str, device_id: str, details: dict):
        record = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "event_type": event_type,
            "device_id": device_id,
            "details": details
        }
        try: self.queue.put_nowait(record)
        except Exception: pass

    def _worker(self):
        with open(self.log_path, "a", encoding="utf-8") as f:
            while self.running:
                try:
                    rec = self.queue.get(timeout=1.0)
                    f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                    f.flush()
                    self.queue.task_done()
                except Empty:
                    continue

audit_logger = AsyncAuditLogger(AUDIT_LOG_FILE)

# --- WIRE PROTOCOL DECODER ---

def decode_wire_header(data: bytes):
    if len(data) < HEADER_SIZE + DEVICE_TAG_SIZE:
        return None
    magic, ver, msg_type, flags = struct.unpack_from(">BBBB", data, 0)
    if magic != MAGIC_BYTE or ver != PROTOCOL_VERSION:
        return None
    seq, ts, payload_len = struct.unpack_from(">IQI", data, 4)
    if len(data) < HEADER_SIZE + payload_len or payload_len < DEVICE_TAG_SIZE:
        return None

    # Extrai o DeviceTag ASCII de 4 bytes (ex: b"VND1")
    raw_tag = data[HEADER_SIZE : HEADER_SIZE + DEVICE_TAG_SIZE]
    device_tag = raw_tag.decode("ascii", errors="ignore").replace("_", "").strip().upper()
    payload = data[HEADER_SIZE + DEVICE_TAG_SIZE : HEADER_SIZE + payload_len]

    return {
        "msg_type": msg_type,
        "flags": flags,
        "seq": seq,
        "timestamp": ts,
        "device_tag": device_tag or "VND1",
        "payload": payload
    }

def decode_telemetry(payload: bytes):
    try:
        offset = 0
        state_mode = payload[offset]; offset += 1
        dev_id_len = struct.unpack_from(">H", payload, offset)[0]; offset += 2
        dev_id = payload[offset:offset+dev_id_len].decode("utf-8"); offset += dev_id_len
        battery = payload[offset]; offset += 1
        is_charging = (payload[offset] == 1); offset += 1
        pkg_len = struct.unpack_from(">H", payload, offset)[0]; offset += 2
        pkg = payload[offset:offset+pkg_len].decode("utf-8"); offset += pkg_len
        cpu, ram = struct.unpack_from(">fq", payload, offset)
        mode_str = {0: "IDLE", 1: "FOCUS", 2: "LIVE"}.get(state_mode, "IDLE")

        return {
            "device_id": dev_id, "mode": mode_str, "battery_pct": battery,
            "is_charging": is_charging, "focused_package": pkg,
            "cpu_usage_pct": round(cpu, 1), "ram_usage_mb": ram
        }
    except Exception:
        return None

# --- ENDPOINTS REST HTTP ---

@app.route("/api/status", methods=["GET"])
@app.route("/api/devices", methods=["GET"])
def get_status():
    """
    Endpoint leve consumido via fetch() pelo OpsGrid do React no Vercel.
    Retorna o estado dos dispositivos sem precisar abrir WebSocket persistente.
    """
    return jsonify({
        "success": True,
        "server_ip": request.host.split(":")[0],
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "count": len(rooms.get_status_summary()),
        "devices": rooms.get_status_summary()
    })

@app.route("/api/device/<device_id>/command", methods=["POST"])
def send_remote_command(device_id: str):
    data = request.json or {}
    cmd = data.get("command", "SET_MODE")
    mode = data.get("mode", "LIVE")
    cmd_id = f"cmd_{int(time.time()*1000)}"

    payload = {
        "action": cmd,
        "commandId": cmd_id,
        "mode": mode,
        "timestamp": int(time.time() * 1000)
    }

    target_tag = device_id.replace("device_", "").strip().upper()
    dev_ws = rooms.get_device_ws(target_tag)
    if not dev_ws:
        return jsonify({"success": False, "error": f"Dispositivo [{target_tag}] offline"}), 404

    try:
        dev_ws.send(json.dumps(payload))
        audit_logger.log("COMMAND_SENT", target_tag, {"command": cmd, "mode": mode, "origin": request.remote_addr})
        return jsonify({"success": True, "commandId": cmd_id, "status": "SENT"})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route("/api/audit-logs", methods=["GET"])
def get_audit_logs():
    limit = int(request.args.get("limit", 100))
    logs = []
    if os.path.exists(AUDIT_LOG_FILE):
        with open(AUDIT_LOG_FILE, "r", encoding="utf-8") as f:
            for line in reversed(f.readlines()[-limit:]):
                try: logs.append(json.loads(line.strip()))
                except Exception: continue
    return jsonify({"success": True, "logs": logs})

# --- WEBSOCKETS (ROOM-BASED FAN-OUT) ---

@sock.route("/ws/device/<device_tag>")
def device_websocket(ws, device_tag: str):
    tag = device_tag.strip().upper()
    rooms.register_device(tag, ws)
    audit_logger.log("DEVICE_CONNECTED", tag, {"ip": str(request.remote_addr)})
    logger.info(f"Celular [{tag}] conectado na rede local Wi-Fi.")

    try:
        while True:
            message = ws.receive()
            if message is None: break

            now_ms = int(time.time() * 1000)
            if isinstance(message, (bytes, bytearray)):
                header = decode_wire_header(message)
                if not header: continue

                msg_type = header["msg_type"]
                seq = header["seq"]
                tag = header["device_tag"]
                room = f"device_{tag}"
                latency_ms = max(0.0, float(now_ms - header["timestamp"]))

                if msg_type == TYPE_TELEMETRY:
                    telem = decode_telemetry(header["payload"])
                    if telem:
                        rooms.update_telemetry(tag, telem["mode"], telem["battery_pct"],
                                               telem["is_charging"], telem["focused_package"],
                                               telem["cpu_usage_pct"], telem["ram_usage_mb"],
                                               seq, latency_ms)
                        # Notifica operadores da sala
                        broadcast_telemetry_to_room(room, tag, telem, latency_ms)

                elif msg_type == TYPE_VIDEO_NAL or msg_type == TYPE_AUDIO:
                    # ROOM-BASED FAN-OUT IMEDIATO (< 1ms na rede local Wi-Fi)
                    subscribers = rooms.get_room_subscribers(room)
                    for sub in subscribers:
                        try: sub.send(message)
                        except Exception: rooms.unsubscribe_operator(sub)

                elif msg_type == TYPE_HEARTBEAT:
                    # Responde ao ping de health check de 30s
                    ws.send(json.dumps({"type": "PONG", "timestamp": now_ms}))

    except Exception as e:
        logger.warning(f"Conexão do device [{tag}] interrompida: {e}")
    finally:
        rooms.set_offline(tag)
        audit_logger.log("DEVICE_OFFLINE", tag, {})
        logger.info(f"Dispositivo [{tag}] desconectado.")

@sock.route("/ws/operator")
def operator_websocket(ws):
    """
    WebSocket do Dashboard React no Vercel (Lazy Connection).
    Só é aberto quando o operador clica para visualizar a live.
    """
    current_room: Optional[str] = None
    logger.info(f"Novo monitor React conectado do IP: {request.remote_addr}")

    try:
        while True:
            msg = ws.receive()
            if msg is None: break

            if isinstance(msg, str):
                try:
                    data = json.loads(msg)
                    action = data.get("action")

                    if action == "SUBSCRIBE":
                        target_id = data.get("deviceId", "VND1").replace("device_", "").strip().upper()
                        current_room = f"device_{target_id}"
                        rooms.subscribe_operator(current_room, ws)

                        # Ativa modo LIVE e força I-Frame IDR imediato ao vendedor
                        dev_ws = rooms.get_device_ws(target_id)
                        if dev_ws:
                            try:
                                dev_ws.send(json.dumps({"action": "SET_MODE", "mode": "LIVE"}))
                                dev_ws.send(json.dumps({"action": "REQUEST_KEYFRAME"}))
                            except Exception as e:
                                logger.warning(f"Falha ao notificar device: {e}")

                        ws.send(json.dumps({
                            "type": "SUBSCRIBED",
                            "room": current_room,
                            "deviceId": target_id,
                            "timestamp": datetime.now(timezone.utc).isoformat()
                        }))

                    elif action == "UNSUBSCRIBE":
                        rooms.unsubscribe_operator(ws)
                        current_room = None

                    elif action in ("SEND_COMMAND", "COMMAND"):
                        target_id = data.get("deviceId", "VND1").replace("device_", "").strip().upper()
                        cmd = data.get("command") or data.get("action_type") or "REQUEST_KEYFRAME"
                        params = data.get("params") or {}
                        dev_ws = rooms.get_device_ws(target_id)
                        if dev_ws:
                            cmd_payload = {"action": cmd, **params}
                            try:
                                dev_ws.send(json.dumps(cmd_payload))
                                logger.info(f"Comando [{cmd}] despachado para [{target_id}] via operator socket.")
                            except Exception as e:
                                logger.warning(f"Erro ao enviar comando para [{target_id}]: {e}")

                except Exception as e:
                    logger.error(f"Erro ao processar mensagem do operador: {e}")

    except Exception:
        pass
    finally:
        rooms.unsubscribe_operator(ws)
        logger.info("Monitor React desconectado.")

def broadcast_telemetry_to_room(room: str, tag: str, telem: dict, latency_ms: float):
    subs = rooms.get_room_subscribers(room)
    if not subs: return
    msg = json.dumps({
        "type": "TELEMETRY_UPDATE",
        "deviceId": tag,
        "room": room,
        "latencyMs": latency_ms,
        "data": telem
    })
    for sub in subs:
        try: sub.send(msg)
        except Exception: rooms.unsubscribe_operator(sub)

if __name__ == "__main__":
    # Registra salas dos vendedores no catálogo inicial
    rooms.register_device("VND1")
    rooms.register_device("VND2")
    rooms.register_device("VND3")

    logger.info("Digital Twin Local Gateway escutando em 0.0.0.0:5000 (Wi-Fi Local)...")
    app.run(host="0.0.0.0", port=5000, threaded=True, debug=False)
