# KozyrChunkyQueue

Аддон к [Chunky](https://modrinth.com/plugin/chunky) для Paper-совместимых серверов (Paper/Purpur/Leaf/Pufferfish и т.п.), который:

- автоматически прогружает цепочку миров по очереди без ручных команд;
- ставит прогрузку на паузу (или троттлит по числу потоков) на время, пока на сервере есть игроки, и продолжает её именно с того места, где остановилась;
- переживает рестарт сервера посреди прогрузки, продолжая мир, который не успел догрузиться;
- умеет отдельным режимом "простой генерации" явно задавать радиус/центр для каждого мира из конфига.

## Требования

- Paper-совместимое ядро (Purpur/Leaf/Pufferfish подходят, голый Spigot/CraftBukkit — нет, для режима `throttle` нужен `config/paper-global.yml`)
- Java 25
- [Chunky](https://modrinth.com/plugin/chunky) (Bukkit-сборка) той же ветки Minecraft, что и сервер

## Установка

1. Положить `Chunky-Bukkit-*.jar` и `KozyrChunkyQueue-*.jar` в `plugins/`.
2. Запустить сервер один раз, чтобы сгенерировался `plugins/KozyrChunkyQueue/config.yml`.
3. Отредактировать конфиг под себя, `/chunkyqueue reload` (или рестарт).

## Команды и права

| Команда | Право | Описание |
|---|---|---|
| `/chunkyqueue status` | `kozyrchunkyqueue.status` (по умолчанию: op) | Текущее состояние очереди |
| `/chunkyqueue stop` | `kozyrchunkyqueue.admin` (по умолчанию: op) | Остановить очередь вручную |
| `/chunkyqueue resume` | `kozyrchunkyqueue.admin` | Возобновить остановленную очередь |
| `/chunkyqueue reload` | `kozyrchunkyqueue.admin` | Перечитать config.yml на лету |

## Конфиг

```yaml
on-players-online: pause   # pause | throttle
throttle-worker-threads: 2
auto-start: true
queue-order: world, world_nether, world_the_end

simple-generation:
  enabled: false
  order: world, world_nether, world_the_end
  worlds:
    world:
      radius: 15000
      center: 0 0
    ...
```

- **`on-players-online: pause`** — прогрузка полностью останавливается, пока на сервере есть хотя бы один игрок, и мгновенно возобновляется, когда сервер пустеет.
- **`on-players-online: throttle`** — вместо паузы уменьшает число потоков генерации Chunky (`chunk-system.worker-threads` в `paper-global.yml`) до `throttle-worker-threads`. Требует рестарта сервера, чтобы применить новое число потоков, и держит эту урезанную скорость постоянно (не только при живых игроках) — если нужна полная скорость на пустом сервере, используйте `pause`.
- **`queue-order`** — классический режим: миры прогружаются с текущими настройками Chunky (радиус/центр не переопределяются).
- **`simple-generation`** — режим, в котором радиус и центр каждого мира берутся из конфига, а не из состояния Chunky.

Плагин переживает рестарт сервера и любые паузы: если прогрузка мира была прервана (сервер перезапущен, игрок зашёл, `/chunkyqueue stop`), при следующем запуске/резюме она продолжится через `chunky continue` с того же места, а не начнётся заново.

## Помощь и обратная связь

Баги и вопросы — в Discord сообщества KOZYR, раздел **🗺 CHUNKY QUEUE** (каналы «баги» и «помощь»): https://discord.gg/6g6VqENtvU

При сообщении о баге укажите версию сервера (Paper/Purpur/Leaf…), версию Chunky и плагина, приложите `config.yml` и лог.

## Сборка из исходников

Без Maven/Gradle — просто `javac` + `jar`. Скрипт сам скачивает нужные для компиляции jar'ы (paper-api, Chunky, adventure, bungeecord-chat, jetbrains annotations) в `.deps/` (не входит в git).

```bash
./build.sh        # Linux/macOS
./build.ps1        # Windows (PowerShell)
```

Готовый jar появится в `build/KozyrChunkyQueue-<версия>.jar`. По умолчанию собирается под Paper `26.2.build.87-stable` / Chunky `1.5.3` (Java 25) — актуальные версии на момент публикации; при необходимости переопределяются переменными окружения `PAPER_API_VERSION`, `CHUNKY_VERSION_ID`, `JAVAC`, `JAR_EXE`.

## Лицензия

MIT — см. [LICENSE](LICENSE).
