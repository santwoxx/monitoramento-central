# 🛡️ VendorOps - Digital Twin Ops Center

Sistema de telemetria em tempo real e monitoramento de baixa latência (< 200ms) para frotas de dispositivos Android corporativos ("Digital Twin").

Projetado com arquitetura **Direct-to-Local (Wi-Fi)**:
* **Frontend**: React + Vite hospedado no **Vercel** (estáticos) ou executado localmente.
* **Gateway de Ingestão**: Python Flask + WebSocket escutando em `0.0.0.0:5000` na estação local.
* **Dispositivos Android**: Pipeline modular Kotlin (H.264 Baseline, PCM/AAC, Room DB Outbox, Foreground Service).
* **Segurança**: Todo o tráfego pesado de mídia (vídeo e áudio) permanece **exclusivamente na rede local Wi-Fi**, sem transitar por túneis em nuvem.

---

## 📐 Topologia de Rede

```
┌─────────────────────────────────┐
│     Operador (Navegador)        │
│  Hospedado no Vercel (Estático) │
└────────────────┬────────────────┘
                 │ Fetch HTTP & WebSocket (Direct-to-Local)
                 ▼
┌─────────────────────────────────┐           ┌────────────────────────────────┐
│   PC Gateway Local (Python)     │ ◄-Wi-Fi-- │   Smartphones Android (VNDx)   │
│       0.0.0.0:5000              │           │   MediaProjection + AAC Audio  │
│  Fan-Out por Salas (device_VNDx)│           │   Outbox FIFO (Room SQLite)    │
└─────────────────────────────────┘           └────────────────────────────────┘
```

---

## 🚀 Guia de "Setup da Loja" (Passo a Passo)

### 1. Descoberta do IP do PC Local

O PC onde o `app.py` será executado deve estar conectado na mesma sub-rede Wi-Fi dos celulares.

1. No Windows, abra o PowerShell ou Prompt de Comando e execute:
   ```powershell
   ipconfig
   ```
2. Localize o seu adaptador de rede ativo (ex: `Adaptador Ethernet` ou `Adaptador de Rede Sem Fio Wi-Fi`) e anote o **Endereço IPv4**:
   * Exemplo: `192.168.1.116`

---

### 2. Executando o Gateway Local (Python)

#### Pré-requisitos:
* Python 3.9+ instalado ([python.org](https://www.python.org/downloads/)).
* Durante a instalação do Python no Windows, marque a opção **"Add Python to PATH"**.

#### Execução:
1. Abra um terminal na pasta `server`:
   ```bash
   cd server
   ```
2. (Opcional, recomendado) Crie e ative um ambiente virtual:
   ```bash
   python -m venv venv
   # No Windows (PowerShell):
   .\venv\Scripts\Activate.ps1
   # No Linux/macOS:
   source venv/bin/activate
   ```
3. Instale as dependências:
   ```bash
   pip install -r requirements.txt
   ```
4. Inicie o servidor:
   ```bash
   python app.py
   ```
5. O servidor iniciará em `0.0.0.0:5000`. Ele responderá a:
   * **Status REST**: `GET http://<SEU_IP_LOCAL>:5000/api/status`
   * **Ingestão Android**: `ws://<SEU_IP_LOCAL>:5000/ws/device/<DEVICE_TAG>`
   * **Streaming do Operador**: `ws://<SEU_IP_LOCAL>:5000/ws/operator`

---

### 3. Compilando e Instalando o APK Android

O projeto Android é multi-módulo (`:app`, `:core-database`, `:core-network`, `:core-media`, `:core-state`, `:feature-overlay`).

#### Pré-requisitos:
* Android Studio Iguana / Jellyfish (ou JDK 17+ com Android SDK 34).

#### Compilação via Linha de Comando:
1. Abra o terminal na pasta `android`:
   ```bash
   cd android
   ```
2. (Opcional) Copie o arquivo de exemplo de propriedades locais:
   ```bash
   copy local.properties.example local.properties
   ```
   Defina o caminho do seu SDK se necessário (`sdk.dir=C:\\Users\\SeuUsuario\\AppData\\Local\\Android\\Sdk`).
3. Compile o APK em modo Debug:
   ```bash
   ./gradlew assembleDebug
   # No Windows:
   gradlew.bat assembleDebug
   ```
4. O APK gerado estará em:
   `android/app/build/outputs/apk/debug/app-debug.apk`

#### Instalação via ADB:
Com o celular conectado via cabo USB (com Depuração USB ativada):
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### Configuração no Primeiro Uso:
1. Abra o aplicativo **"Digital Twin Client"**.
2. Digite o **IP do PC Local** (ex: `192.168.1.116`) e a **Tag do Vendedor** (ex: `VND1`, `VND2` ou `VND3`).
3. Clique em **"Iniciar Monitoramento"**.
4. Conceda a permissão de captura de tela (**MediaProjection**) no diálogo do Android.
5. O ícone de Foreground Service será exibido e o dispositivo conectará no PC.

---

### 4. Build e Deploy do Frontend (Vercel & Local)

#### Executando Localmente (Desenvolvimento / Operação Local):
1. Abra o terminal na pasta `web`:
   ```bash
   cd web
   ```
2. Instale as dependências:
   ```bash
   npm install
   ```
3. Inicie o servidor de desenvolvimento:
   ```bash
   npm run dev
   ```
4. Acesse no navegador: `http://localhost:5173`.
5. No cabeçalho, insira o IP do seu PC local (ex: `192.168.1.116`) e clique em **"Salvar"**. O IP fica memorizado no `LocalStorage`.

#### Compilando para Produção:
```bash
npm run build
```
O build estático otimizado é gerado na pasta `web/dist/`.

#### Deploy no Vercel:
O arquivo `web/vercel.json` já está preparado com framework `vite` e cabeçalhos estáticos (sem rewrites desnecessários).

1. Via Vercel CLI:
   ```bash
   cd web
   vercel --prod
   ```
2. Via Interface Web do Vercel:
   * Conecte este repositório Git.
   * Defina o **Root Directory** como `web`.
   * O build command será automaticamente `npm run build` e o output directory será `dist`.

> [!IMPORTANT]
> **Acesso Seguro do Navegador (Private Network Access):**
> Se você acessar o dashboard via HTTPS (`https://seu-projeto.vercel.app`), os navegadores modernos (Chrome/Edge) aplicam a política de *Private Network Access (PNA)* para proteger IPs locais (`192.168.x.x`). O `app.py` já responde automaticamente com o cabeçalho `Access-Control-Allow-Private-Network: true`. Para conexões WebSocket (`ws://`), certifique-se de permitir conteúdo inseguro nas configurações do site no Chrome ou utilize o frontend em HTTP local para operação em tempo real sem avisos do navegador.

---

## 📂 Estrutura do Repositório

```text
├── android/                        # Projeto Android Nativo (Kotlin Multi-Module)
│   ├── app/                        # Orquestrador DigitalTwinCoordinator & MainActivity
│   ├── core-database/              # Room DB, OutboxEntity & OutboxDao (FIFO Queue)
│   ├── core-network/               # ResilientWebSocketClient, Backoff, BinaryProtocol
│   ├── core-media/                 # MediaProjectionPipeline (H.264) & AudioCapture
│   ├── core-state/                 # TelemetryStateFlow & Monitoramento de Hardware
│   ├── feature-overlay/            # Watermark de hardware & Foreground Service
│   ├── settings.gradle.kts         # Configuração dos 6 submódulos
│   └── build.gradle.kts            # Plugins Android 8.2 e Kotlin 1.9
├── server/                         # Gateway de Ingestão e Fan-Out (Python)
│   ├── app.py                      # Flask + Sock WebSocket (0.0.0.0:5000)
│   ├── requirements.txt            # Dependências Python (Flask, flask-sock, flask-cors)
│   └── audit.log                   # Registro assíncrono de eventos (ignorado no git)
├── web/                            # Dashboard de Operações (React + Vite)
│   ├── src/
│   │   ├── components/
│   │   │   ├── LiveMonitor.jsx     # Player MSE H.264 com Init Segment e Buffer Pruning
│   │   │   ├── OpsGrid.jsx         # Grid de terminais com Seletor de IP Local
│   │   │   ├── ContextFeed.jsx     # Feed de eventos corporativos em tempo real
│   │   │   └── AuditTrail.jsx      # Visualizador de trilha de auditoria
│   │   ├── App.jsx                 # Estado global, roteamento e LocalStorage
│   │   └── index.css               # Design System dark mode
│   ├── package.json                # Dependências Web (React 18, Vite 5)
│   └── vercel.json                 # Configuração de deploy estático no Vercel
├── .gitignore                      # Regras de exclusão de artefatos de build e secrets
└── README.md                       # Documentação técnica e operacional
```

---

## 🔒 Auditoria de Segurança e Resiliência

1. **Direct-to-Local Media Isolation**: Vídeo e áudio nunca transitam fora da LAN. O dashboard no Vercel atua como camada de apresentação estática, estabelecendo socket diretamente com o gateway local.
2. **MSE Init Segment Dinâmico**: O `LiveMonitor.jsx` sintetiza os boxes ISO BMFF (`ftyp` + `moov` com bloco `avcC`) assim que os primeiros NALs SPS (0x07) e PPS (0x08) chegam, permitindo decodificação instantânea do fluxo H.264 Baseline no navegador.
3. **Buffer Pruning (< 200ms)**: Caso a reprodução acumule atraso superior a 250ms no SourceBuffer, o player automaticamente salta para a borda viva da transmissão (`currentTime = bufferedEnd - 0.05s`).
4. **Outbox Pattern Resiliente**: Quedas de Wi-Fi ativam o buffering no SQLite local via Room (`telemetry_outbox`). Ao restabelecer a conexão, o cliente drena os pacotes em estrita ordem FIFO com backoff exponencial e jitter.
5. **I-Frame On Demand**: Sempre que um operador abre o monitor no Vercel, o gateway despacha `REQUEST_KEYFRAME` para o dispositivo, acionando `forceKeyFrame()` no `MediaCodec` para sincronização imediata.
