# P2P-WebRtc-java
Java + WebRTC P2P стриминг: расширение захватывает камеру/микрофон, мост публикует на любую страницу, зрители получают поток напрямую через WebRTC.

Одноранговая (peer-to-peer) трансляция видео/аудио по WebRTC:
минимальный Java-сервер сигналинга на WebSocket + расширение Chrome
(Manifest V3), которое захватывает камеру/микрофон и публикует поток
зрителям на любой обычной веб-странице.

Без SFU, без медиасервера — медиа идёт напрямую между пирами.
Сервер нужен только для обмена SDP/ICE-кандидатами.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Chrome extension                                                    │
│  ┌──────────┐   ┌─────────────────┐   ┌────────────────────┐         │
│  │ popup.js │──▶│  background.js  │──▶│ capture.html/js    │         │
│  │          │   │  (service wrk.) │   │ getUserMedia       │         │
│  └──────────┘   └────────┬────────┘   │ RTCPeerConnection  │         │
│                          │ inject     └──────────┬─────────┘         │
│                          ▼                       │ signaling         │
│                  ┌───────────────┐               │                   │
│                  │  bridge.js    │◀──────────────┘                   │
│                  │ (any tab)     │                                   │
│                  └───────┬───────┘                                   │
└──────────────────────────┼───────────────────────────────────────────┘
                           │  ws://server:8080/ws/publish?room=X
                           ▼
                  ┌──────────────────┐
                  │  WebVideoChat    │   ← Java, 1 файл, ~600 строк
                  │  WS signaling    │
                  └────────┬─────────┘
                           │  ws://server:8080/ws/subscribe?room=X
                           ▼
                  ┌──────────────────┐
                  │  www/index.html  │   ← viewer, открыт в браузере
                  │  RTCPeerConnection│
                  └──────────────────┘

         ◀══════════ WebRTC P2P (медиа напрямую) ══════════▶
```

---

## Возможности

- **P2P-медиа** — видео/аудио идёт напрямую между браузерами, сервер
  в передаче не участвует.
- **Источник — расширение Chrome** — камера/микрофон захватываются в
  отдельном окне расширения (`capture.html`), поэтому не требуется
  HTTPS и разрешения на публикацию в самом веб-приложении.
- **Мост в любую страницу** — `bridge.js` инжектится в произвольную
  http(s)-вкладку и открывает WebSocket к серверу сигналинга. Открывать
  страницу именно на сервере — не обязательно.
- **Комнаты** — один сервер обслуживает несколько независимых комнат
  (`?room=main`, `?room=test` и т.д.).
- **Один файл сервера** — `WebVideoChat.java`, без фреймворков; статика
  (`www/`) отдаётся тем же процессом.
- **Виртуальные потоки** — соединения обслуживаются `Thread.ofVirtual()`
  (требуется Java 21+).
- **MV3** — расширение на актуальном Manifest V3.

---

## Требования

| Компонент | Версия |
|---|---|
| Java (JDK) | **21+** (используется `Thread.ofVirtual()`) |
| Библиотека | [`org.json:json`](https://mvnrepository.com/artifact/org.json/json) |
| Браузер | Chrome / Chromium / Edge — с поддержкой MV3 |
| ОС сервера | любая (Linux, macOS, Windows) |

---

## Быстрый старт

### 1. Сервер

```bash
# скачать зависимость
curl -LO https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar

# скомпилировать
javac -cp json-20240303.jar WebVideoChat.java

# положить рядом папку www/ с index.html (см. ниже)
# запустить
java -cp ".:json-20240303.jar" WebVideoChat 8080      # Linux / macOS
java -cp ".;json-20240303.jar" WebVideoChat 8080      # Windows
```

Откройте в браузере: **http://localhost:8080/** — это страница зрителя.

### 2. Расширение

1. Откройте `chrome://extensions/`.
2. Включите **Developer mode** (переключатель справа сверху).
3. **Load unpacked** → выберите папку с расширением (там, где
   `manifest.json`).
4. Запомните, что расширение появилось на панели.

### 3. Трансляция

1. Откройте **любую** http(s)-страницу в новой вкладке (можно даже
   `http://example.com/`).
2. Кликните по иконке расширения.
3. В поле **Server** укажите `IP:порт` вашего сервера (например,
   `192.168.1.100:8080`), поле **Room** оставьте `main`.
4. **Start broadcasting** → откроется маленькое окно с превью
   камеры. Разрешите доступ к камере/микрофону.
5. Откройте у зрителей `http://<server>:8080/`, нажмите **Watch** —
   видео пойдёт напрямую.

---

## Установка подробнее

### Сервер

**Структура каталогов** (рабочая директория процесса = каталог с `www/`):

```
./
├── WebVideoChat.java
├── WebVideoChat.class
├── json-20240303.jar
└── www/
    └── index.html
```

Сервер ищет статику в `./www/` относительно **текущей рабочей
директории** (см. `WWW_ROOT = Paths.get("www")`). Если запускаете из
другого каталога — используйте `cd` перед запуском.

**Запуск:**

```bash
java -cp ".:json-20240303.jar" WebVideoChat 8080
```

Первый аргумент — порт (по умолчанию `8080`).

**Вывод при старте:**

```
WebVideoChat
  port : 8080
  www  : /path/to/www
```

### Расширение

Структура папки расширения:

```
extension/
├── manifest.json
├── background.js
├── bridge.js
├── capture.html
├── capture.js
├── popup.html
└── popup.js
```

Загружается как unpacked (см. шаг 2 выше). После изменений в коде —
кнопка **Reload** на карточке расширения в `chrome://extensions/`.

> При первом нажатии **Start broadcasting** браузер спросит разрешение
> на камеру и микрофон. Это происходит в окне `capture.html` — то есть
> источником является расширение, а не открытая веб-страница.

---

## Использование

### Поле «Server»

Принимает:

| Ввод | Что получится |
|---|---|
| `192.168.1.100:8080` | `ws://192.168.1.100:8080` |
| `http://192.168.1.100:8080` | `ws://192.168.1.100:8080` |
| `https://cast.example.com` | `wss://cast.example.com` |
| `ws://myhost:8080` | `ws://myhost:8080` |
| `wss://myhost` | `wss://myhost` |
| *(пусто)* | host текущей вкладки |

### Страница зрителя

`http://<server>:8080/` — открывает `www/index.html`:

- Поле **Room** — имя комнаты.
- **Watch** — подключиться к `/ws/subscribe?room=<room>`.
- **Stop** — закрыть соединение и освободить ресурсы.
- **🔊 Включить звук** — появляется, если Chrome заблокировал
  автозапуск аудио (политика autoplay).

Также можно передать комнату параметром:
`http://<server>:8080/?room=test` — поле подставится автоматически.

### Сценарий «страница на другом хосте»

1. Открываете `http://example.com/` (любая http-страница).
2. В popup расширения вводите `192.168.1.100:8080`, Room `main`.
3. Start → расширение инжектит `bridge.js` в `example.com` и
   подключается к `ws://192.168.1.100:8080/ws/publish?room=main`.
4. Зрители открывают `http://192.168.1.100:8080/`, Room `main`,
   нажимают Watch.

Медиа в этом сценарии идёт peer-to-peer — `example.com` вообще не
участвует в трафике.

---

## Архитектура и протокол

### HTTP

| Метод | Путь | Назначение |
|---|---|---|
| `GET` / `HEAD` | `/*` | статика из `www/` (защита от path traversal) |
| `GET` (Upgrade) | `/ws/publish?room=X` | WebSocket — публикатор |
| `GET` (Upgrade) | `/ws/subscribe?room=X` | WebSocket — зритель |

Остальные методы → `405 Method Not Allowed`.

### WebSocket — публикатор (`/ws/publish`)

Сервер присылает публикатору:

```jsonc
{ "viewerJoined": "<uuid>" }                    // новый зритель
{ "viewerLeft":   "<uuid>" }                    // зритель отключился
{ "sdp":       { ... }, "viewerId": "<uuid>" }  // SDP-answer от зрителя
{ "candidate": { ... }, "viewerId": "<uuid>" }  // ICE от зрителя
```

Публикатор отправляет в сокет (роутинг по полю `target`):

```jsonc
{ "target": "<viewerId>", "sdp":       { ... } }
{ "target": "<viewerId>", "candidate": { ... } }
```

### WebSocket — зритель (`/ws/subscribe`)

Первое сообщение от сервера зрителю:

```json
{ "viewerId": "<uuid>" }
```

Далее сервер ретранслирует от публикатора:

```jsonc
{ "sdp":       { ... } }
{ "candidate": { ... } }
```

Зритель отправляет (всегда `target: "publisher"`):

```jsonc
{ "target": "publisher", "sdp":       { ... } }
{ "target": "publisher", "candidate": { ... } }
```

### Порядок установления P2P

```
publisher                      server                      viewer
    │                             │                            │
    │  ws /ws/publish?room=main   │                            │
    ├────────────────────────────▶│                            │
    │                             │  ws /ws/subscribe?room=main│
    │                             │◀───────────────────────────┤
    │  {"viewerJoined":"<uuid>"}  │ {"viewerId":"<uuid>"}      │
    │◀────────────────────────────┼───────────────────────────▶│
    │                             │                            │
    │  createOffer()              │                            │
    │  {"target":"<uuid>","sdp":…}│                            │
    ├────────────────────────────▶├── {"sdp":…} ──────────────▶│
    │                             │                            │
    │                             │       setRemote(offer)     │
    │                             │       createAnswer()       │
    │                             │◀─ {"target":"publisher",…}─┤
    │  {"sdp":…,"viewerId":"<u>"} │                            │
    │◀────────────────────────────┤                            │
    │                             │                            │
    │  ═════ ICE trickle (оба направления) ═══════════════════ │
    │                             │                            │
    │  ══════════════ WebRTC P2P (медиа напрямую) ═════════════ │
    │◀───────────────────────────────────────────────────────▶│
```

### Жизненный цикл публикатора

- Одновременно в комнате — **один** активный публикатор.
- Новый `/ws/publish` в той же комнате вытесняет старого: старый
  получает `close(1000, "replaced")`, а все зрители — `close(1000,
  "publisher restarted")` и должны переподключиться.
- При обрыве публикатора зрители не отключаются сервером — они просто
  остаются висеть; при следующем публикаторе получат `viewerJoined`
  повторно (с новыми SDP).

---

## Безопасность

**Проект не предназначен для публичного интернета «как есть».**

Что нужно знать:

- **Аутентификации нет** — любой, кто знает имя комнаты, может
  смотреть или публиковать.
- **TLS нет** — сервер по умолчанию отдаёт `http://` и `ws://`. Для
  `https://`/`wss://` поставьте обратный прокси (nginx, Caddy,
  Traefik) с TLS-сертификатом.
- **Mixed content** — если publisher-вкладка открыта по `https://`,
  браузер заблокирует `ws://`. В поле Server расширения нужно
  указывать `https://host` (→ `wss://`).
- **Проверка Origin отсутствует** — сервер принимает upgrade с любым
  `Origin`. Это удобно для локальной сети, но не для продакшена.
- **Path traversal** — реализована защита: `Path.normalize()` +
  проверка `startsWith(WWW_ROOT)`.

**Минимальные меры для локальной сети:**

- Запускайте на нужном интерфейсе (фаервол / `iptables` / security
  group), чтобы порт был доступен только доверенной подсети.
- Ставьте непредсказуемые имена комнат (`room=6f3a…`), если не
  хотите, чтобы соседи по сети случайно подглядели.
- Для доступа извне — только через TLS-прокси с basic-auth.

---

## Ограничения

| Ограничение | Пояснение |
|---|---|
| **Один публикатор на комнату** | второе подключение к `/ws/publish` вытесняет первое |
| **Нет SFU** | N зрителей = N `RTCPeerConnection` у публикатора, полоса отдачи линейна по N. Практический предел — **5–10 зрителей** на домашнем канале |
| **Только STUN** | сервер использует `stun:stun.l.google.com`. За симметричным NAT без TURN соединение не установится. Для продакшена добавьте TURN (coturn) |
| **Только Chrome-расширение** | MV3; Firefox и Safari не поддерживаются |
| **Нет записи / чата / чата в UI** | несмотря на имя класса `WebVideoChat`, чата нет — только сигналинг WebRTC |
| **Нет переподключения** | при обрыве WS зритель/публикатор должен нажать Start/Watch заново |

---

## Диагностика

### Публикатор: «bridge connect failed: ws error»

- Проверьте, что `Server` в popup указан с портом (`host:8080`).
- Убедитесь, что сервер слушает и доступен:
  `curl -v http://<server>:8080/`.
- Если publisher-вкладка на `https://`, а сервер на `http://` —
  браузер заблокирует соединение (mixed content). Используйте
  `https://` → `wss://`.
- В DevTools вкладки-публикатора (F12 → Console) будут строки
  `[bridge] …`; в окне `capture.html` — `[capture] …`.

### Зритель: «connected, waiting for stream…» — и тишина

- Публикатор не нажал **Start** в popup или не разрешил камеру.
- Публикатор в другой комнате — сверьте `Room` на странице зрителя
  и в popup расширения.
- Открыт `http://` и сервер доступен, но публикатор не в сети —
  сервер пришлёт `viewerJoined` только когда публикатор
  переподключится.

### «Нажмите 🔊 Включить звук»

Chrome блокирует автозапуск аудио. Клик по кнопке разрешает звук.
Это не ошибка проекта.

### Видео идёт, звука нет / наоборот

- Проверьте, что в окне `capture.html` есть индикаторы камеры и
  микрофона (откройте DevTools → вкладка «Tracks» — `[capture]
  Captured tracks: …` в Console).
- `audio: { echoCancellation, noiseSuppression }` включены по
  умолчанию; если микрофон «тихий» — проверьте системный уровень
  записи, не настройки приложения.

### Ошибка компиляции `Thread.ofVirtual()`

Нужен JDK 21+. Проверьте `java -version`. На JDK 17/19 код не
скомпилируется.

### `ClassNotFoundException: org.json.JSONObject`

Забыли `json-*.jar` в `-cp`. Проверьте, что jar лежит рядом и
указан в classpath (`-cp ".:json-20240303.jar"`).

---

## Структура проекта

```
.
├── README.md                    ← этот файл
├── WebVideoChat.java            ← сервер (signaling + static)
│
├── www/
│   └── index.html               ← страница зрителя
│
├── manifest.json                ← MV3
├── background.js                ← service worker
├── bridge.js                    ← content script (мост в страницу)
├── capture.html                 ← окно захвата камеры
├── capture.js                   ← getUserMedia + RTCPeerConnection
├── popup.html                   ← UI расширения
└── popup.js                     ← логика popup
```

---

## Разработка

### Сборка через Maven (опционально)

```xml
<dependency>
    <groupId>org.json</groupId>
    <artifactId>json</artifactId>
    <version>20240303</version>
</dependency>
```

```bash
mvn -q package
java -cp target/classes:target/dependency/* WebVideoChat 8080
```

### Сборка через Gradle

```groovy
dependencies {
    implementation 'org.json:json:20240303'
}
```

```bash
gradle build
java -cp build/classes/java/main:$(gradle -q printClasspath) WebVideoChat 8080
```

### Отладка расширения

1. `chrome://extensions/` → **Reload** карточки после правок.
2. **Service worker** → «Inspect views» → открывается DevTools фонового
   процесса (`background.js`).
3. **Окно capture** — DevTools по F12 прямо в окне превью.
4. **Вкладка-публикатор** — DevTools этой вкладки; там живёт
   `bridge.js`.
5. **Страница зрителя** — обычный DevTools.

### Сетевой дамп сигналинга

WebSocket-кадры видны в DevTools → Network → фильтр «WS» →
подключение → Messages. Удобно для проверки, что `viewerJoined`
доходит и `sdp` уходит в правильный `target`.

---

## Лицензия

Укажите свою (например, MIT / Apache-2.0). Если это внутренний
проект `ru.miacomsoft` — добавьте соответствующее уведомление.

---

## Связанные проекты

- [aiortc](https://github.com/aiortc/aiortc) — WebRTC в Python (если
  понадобится headless-публикатор).
- [coturn](https://github.com/coturn/coturn) — TURN-сервер, если
  нужен обход симметричных NAT.
- [MediaMTX](https://github.com/bluenviron/mediamtx) — если в будущем
  захочется заменить P2P на SFU.
````

---

## `INSTALL.md` (короткая шпаргалка)

Если хочется отдельный «install» для тех, кто не читает README целиком.

````markdown
# Установка за 5 минут

## 1. Сервер

```bash
# Java 21+ обязательна
java -version

mkdir p2p-webcast && cd p2p-webcast

# скачать сервер и www/
git clone <repo-url> .

# зависимость
curl -LO https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar

# собрать и запустить
javac -cp json-20240303.jar WebVideoChat.java
java  -cp ".:json-20240303.jar" WebVideoChat 8080
```

Проверка: `curl http://localhost:8080/` → HTML страницы зрителя.

## 2. Расширение

1. `chrome://extensions/` → Developer mode → Load unpacked → папка
   с `manifest.json`.
2. Иконка расширения появилась на панели.

## 3. Первый тест на одной машине

1. Открыть `http://localhost:8080/` во вкладке A.
2. Вкладка B — любая http-страница (например, `http://example.com/`).
3. Popup расширения: Server = `localhost:8080`, Room = `main`,
   **Start broadcasting**. Разрешить камеру/микрофон.
4. В вкладке A нажать **Watch**.
5. Видео появилось — готово.

## 4. Между двумя машинами

- Сервер запущен на машине с IP `192.168.1.100`.
- У зрителя: `http://192.168.1.100:8080/`.
- У публикатора в popup: Server = `192.168.1.100:8080`.
- Фаервол машины-сервера должен пропускать TCP `8080`.
````

---

## `TROUBLESHOOTING.md` (расширенный, если понадобится вынести из README)

````markdown
# Решение проблем

## Расширение

### «bridge connect failed: timeout»

Сервер не ответил за 5 секунд.

- Сервер запущен? `curl -v http://<host>:<port>/`
- Порт открыт? `nc -zv <host> <port>`
- Server в popup указан с портом? `192.168.1.100:8080`, не просто
  `192.168.1.100`.
- Смотрите `[bridge]` в Console вкладки-публикатора.

### «bridge connect failed: ws error»

WebSocket не открылся.

- На https-странице `ws://` блокируется — используйте `https://host`
  (→ `wss://`).
- Прокси/антивирус режет WebSocket — попробуйте другую сеть.

### Камера/микрофон не запрашиваются

- Открыт ли `capture.html`? Он должен появиться после Start.
- Разрешения Chrome: `chrome://settings/content/camera` и
  `chrome://settings/content/microphone` — проверьте, что
  `chrome-extension://<id>` разрешено.

### После Reload расширения старый capture-попап висит

- Закройте окно вручную.
- Или нажмите **Stop** в popup, затем снова **Start**.

## Сервер

### `java.lang.UnsupportedClassVersionError`

Компилировали на одной версии JDK, запускаете на другой. Пересоберите
на целевой JDK 21+.

### Порт 8080 занят

```bash
ss -ltnp | grep 8080     # Linux
lsof -nP -iTCP:8080      # macOS
netstat -ano | findstr :8080  # Windows
```

Либо запустите на другом порту: `java … WebVideoChat 9090`.

### Статика не отдаётся (404)

Сервер ищет `www/` относительно **текущей рабочей директории**.
Запускайте из каталога, где лежит `www/`, либо используйте `cd`
перед запуском.

## Viewer

### «streaming (WebRTC)», но видео чёрное

- ICE не установился. Откройте DevTools → Console страницы зрителя
  → `[viewer] Connection state: connected|failed`.
- Если `failed` — симметричный NAT. Нужен TURN (coturn) и его
  добавление в `iceServers` в `www/index.html` и `capture.js`.
- Проверьте `chrome://webrtc-internals/` — там видно candidate pairs
  и RTT.

### Автовоспроизведение заблокировано

Chrome требует жест пользователя для звука. Кнопка **🔊 Включить
звук** в UI решает; клик по видео (controls) — тоже.

### Звук есть, видео нет (или наоборот)

- В окне capture DevTools → Console: `[capture] Captured tracks:
  video (…), audio (…)`. Если одного нет — устройство не отдаёт трек.
- Проверьте, что `pc.addTrack(t, stream)` вызывается для всех
  треков (в `capture.js` это `stream.getTracks().forEach(…)`).
