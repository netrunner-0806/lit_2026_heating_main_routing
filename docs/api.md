# HTTP API и форматы данных

API находится под `/api/v1`. Swagger UI доступен по `/swagger-ui.html`, OpenAPI — `/v3/api-docs`.
Ниже приведён фактический контракт контроллеров, включая асинхронный lifecycle и загрузку по частям.
В примерах `BASE=https://localhost`; `curl -k` нужен только для локального сертификата Caddy.
На публичном HTTPS используйте проверку сертификата.

## 1. Входной GeoJSON

Тело файла — `FeatureCollection`, свойства объектов находятся в `properties`.
Координаты WGS84: `[долгота, широта]`.

| `object_type` | Геометрия | Основные свойства |
|---|---|---|
| `source` | Point | `id`, `object_type` |
| `heat_network` | LineString, также поддерживается MultiLineString | `id`, `object_type`, `diameter` |
| `heat_chamber` | Point | `id`, `object_type` |
| `oks_connection_point` | Point | `id`, `object_type`, положительный числовой `flow_tph` |
| `restriction` | LineString, MultiLineString, Polygon, MultiPolygon | `id`, `object_type`, `restriction_type` |

ДУ задаётся в мм, расход в т/ч. ID может быть строкой или числом; тип сохраняется в ссылках.
Повторные ID распознанных объектов, пропущенный ID или повреждённая геометрия потребителя отклоняют вход.
Для расчёта нужны пригодная существующая сеть и точки подключения.

Стандартные ограничения: `oks`, `park`, `social_area`, `prohibited_site`, `water`, `railway`,
`road`, `tram_tracks`, `gas_pipeline`, `power_cable`. Существующая тепловая линия при пересечении
без врезки также проверяется как коммуникация. Здания — ограничения `oks`; принадлежность точки
собственному зданию определяется геометрически. Каждая точка — отдельный потребитель.

Неизвестный `restriction_type` при IGNORE пропускается с предупреждением; FORBIDDEN считает его
запретом с боковым отступом 1 м. Невалидная геометрия не равнозначна неизвестному типу:
невосстановимый объект вызывает диагностику входа. Исправление полигона GeometryFixer сопровождается
предупреждением, поэтому диагностические сообщения нужно читать даже при успешном job.

Небольшой вход для знакомства с режимами: [depth-demo.geojson](../src/test/resources/fixtures/depth-demo.geojson).
Таблицы модели выдаёт `GET /api/v1/reference`: ДУ, вместимости, длины, цены, габариты, камеры,
боковые/вертикальные правила и формула score.

## 2. Создание задания

`POST /api/v1/jobs`, `Content-Type: multipart/form-data`:

| Поле | Обязательность / значение |
|---|---|
| `file` | Обязательный GeoJSON |
| `mode` | PLANAR по умолчанию либо DEPTH |
| `maxVariants` | Если задано — целое 1–3; иначе настройка приложения с ограничением 1–3 |
| `unknownRestrictionPolicy` | IGNORE по умолчанию либо FORBIDDEN |

```bash
BASE=https://localhost
curl -k -F 'file=@input.geojson' -F 'mode=PLANAR' -F 'maxVariants=3' \
  "$BASE/api/v1/jobs"
```

Ответ `202 Accepted` — JobDto. В нём `id`, `status`, времена `createdAt/startedAt/finishedAt`,
`originalFilename`, `inputSize`, `progress`, `error`, `elapsedMs`, счётчики объектов/потребителей/вариантов,
режим и параметры, диагностика, статистика входа и ссылки результата.
Поля, ещё не рассчитанные на данной стадии, могут быть нулевыми или null.
В JobDto режим записывается как `PLANAR`/`DEPTH`; в сводках вариантов и выходном GeoJSON —
`planar`/`depth`. Клиенту следует учитывать это различие существующего контракта.

`202` подтверждает принятие задания, а не валидность GeoJSON и не успех solver. Семантический разбор
происходит асинхронно; его ошибка отражается в FAILED job. Штатный лимит файла — 3 GiB
(3 × 1024³ байт), multipart-запроса — 4 GiB. Для большого файла предпочтителен протокол ниже.

## 3. Статусы и получение результата

```text
QUEUED → RUNNING → DONE
                ↘ FAILED
```

FAILED также возможен при отказе постановки в очередь или восстановлении после аварийного рестарта.
`progress` — текстовое описание стадии, не стабильный числовой процент вычислений.
UPLOADING относится к передаче файла в интерфейсе/upload session, не к перечислению статусов job.

| Метод и путь | Ответ / назначение |
|---|---|
| `GET /api/v1/jobs` | Последние 50 JobDto, без пагинации |
| `GET /api/v1/jobs/{id}` | Статус, диагностика и статистика конкретного job |
| `GET /api/v1/jobs/{id}/result` | JSON со статусом, `metrics`, `inputStats`, `variants`, `diagnostics`, `validation` |
| `GET /api/v1/jobs/{id}/result.geojson` | Файл результата, только DONE; иначе 409 |
| `GET /api/v1/jobs/{id}/input.geojson` | Сохранённый вход |
| `GET /api/v1/jobs/{id}/input-preview.geojson` | Вход до 10 MiB для карты; иначе 204 без тела |
| `GET /api/v1/jobs/{id}/explain` | Объяснения решений и итогового ранжирования по вариантам |
| `GET /api/v1/jobs/{id}/report.json` | Инженерный отчёт по ДУ, переходам, камерам и глубинам, только DONE |
| `GET /api/v1/jobs/{id}/report.csv` | CSV участков, только DONE |

`/result` можно запрашивать до завершения: это JSON текущего состояния, а не сигнал готовности файла.
`validation` содержит `valid`, `errors`, `warnings`, `issues`; issue имеет severity, code, message,
variantId и featureId. Сообщения `diagnostics` описывают вход и вычисления, включая сбой отдельной
стратегии. Их нельзя смешивать с результатом обязательной проверки итогового GeoJSON.

```bash
JOB_ID='ID из ответа создания'
curl -k "$BASE/api/v1/jobs/$JOB_ID"
curl -k "$BASE/api/v1/jobs/$JOB_ID/result"
# После DONE:
curl -k --fail "$BASE/api/v1/jobs/$JOB_ID/result.geojson" -o result.geojson
curl -k "$BASE/api/v1/jobs/$JOB_ID/explain"
```

Итоговая validation FAILED не публикуется как DONE. Отказ одной стратегии при наличии других
завершённых проверенных вариантов может оставить job успешным. Неподключённые потребители включены
в сводку и штраф; DONE не означает автоматически подключение всех точек.

`/explain` возвращает `jobId`, `mode`, `decisions`. По вариантам указаны rank, strategy, события,
`truncated` и `droppedDecisions`: ограниченный журнал не выдаётся за полный перебор.
CSV использует `;`, экранирует значения и защищает пользовательские текстовые поля от формул таблиц.

## 4. Формат выходного GeoJSON

Все варианты входят в один FeatureCollection. Каждый объект содержит `id`, `object_type`, `variant_id`.
Входные объекты повторно не копируются: ссылки на существующую инфраструктуру разрешаются по входу.

| Тип | Геометрия | Основные данные |
|---|---|---|
| `heat_network` | LineString | `start_node_id`, `end_node_id`, `flow_tph`, `diameter`, `length`, `laying_method`, `depth_start`, `depth_end`, `cost` |
| `heat_chamber` | Point | ДУ по максимальному примыкающему участку и стоимость новой камеры |
| `technical_node` | Point | Разбиение линии при смене свойств без ветвления и отдельной цены |
| `variant_summary` | null | `rank`, `score`, стоимости, длина, счётчики и `unconnected_oks_ids` |

`laying_method` — `base` либо `special`; коэффициенты поясняют `k_spec`, `k_depth`.
`crossed_restrictions` и DEPTH-данные описывают переходы, но не заменяют проверку исходной геометрии.
Длины — горизонтальная проекция в метрах, расходы — т/ч, цена — рубли. PLANAR выдаёт null в полях
глубины; DEPTH — метры до верха габарита. Направление записи координат не определяет физический поток.

`construction_cost` складывает новые линии, камеры и врезки; `calculated_cost` добавляет
`unconnected_penalty`. `rank=1` соответствует лучшему score. До трёх вариантов — верхний предел,
а не обещание выдать три на любом входе. Пустой набор новых линий всё ещё может иметь сводку варианта;
режим определяется явно, а не по наличию глубины у первой линии.

## 5. Возобновляемая загрузка

Контроллер: [UploadController](../src/main/java/ru/lct/heatnet/api/UploadController.java).
Все запросы сессии требуют `X-Upload-Token` — случайный клиентский секрет из 64 строчных hex-символов.
Он не включается в URL; сервер сохраняет SHA-256 токена. Для публичного доступа нужен HTTPS.

### Создать сессию

`POST /api/v1/uploads`, `Content-Type: application/json`.
Клиент до первого запроса создаёт `uploadId` (UUID), fingerprint и секрет `X-Upload-Token`.
Тот же секрет передаётся отдельным HTTP-заголовком при создании и во всех дальнейших запросах
этой сессии: status, part, complete и cancel. В JSON и URL он не передаётся.
Хотя заголовок необязателен в аннотации контроллера, сервис требует его: без него возвращается 403.

**Тело запроса создания** (это не ответ сервера):

```json
{
  "uploadId": "UUID, созданный клиентом",
  "filename": "input.geojson",
  "totalSize": 104857600,
  "chunkSize": 16777216,
  "fingerprint": "SHA-256 идентичности файла, 64 строчных hex-символа",
  "mode": "PLANAR",
  "maxVariants": 3,
  "unknownRestrictionPolicy": "IGNORE"
}
```

UUID и fingerprint в JSON выше — placeholders, их нужно заменить. Обязательные поля тела:
`uploadId`, `filename` (1–512 символов), `totalSize` (положительное число не выше лимита файла)
и `fingerprint` (64 строчных hex-символа). Остальные поля можно не передавать: DTO задаёт
`chunkSize=16777216`, `mode=PLANAR`, `maxVariants=3`, `unknownRestrictionPolicy=IGNORE`.
Допустимы части 8–32 MiB; последняя содержит остаток. POST-метаданные ограничены 8 KiB.

Минимальный запрос для локального файла `input.geojson` (Python 3, OpenSSL, shasum и curl):

```bash
UPLOAD_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
UPLOAD_TOKEN=$(openssl rand -hex 32)
FILE_SIZE=$(wc -c < input.geojson | tr -d '[:space:]')
FINGERPRINT=$(shasum -a 256 input.geojson | cut -d ' ' -f 1)
curl -k -X POST "$BASE/api/v1/uploads" \
  -H 'Content-Type: application/json' -H "X-Upload-Token: $UPLOAD_TOKEN" \
  --data "{\"uploadId\":\"$UPLOAD_ID\",\"filename\":\"input.geojson\",\"totalSize\":$FILE_SIZE,\"fingerprint\":\"$FINGERPRINT\"}"
```

Здесь клиент использует полный SHA-256 как fingerprint; это допустимо для сервиса, но отличается
от браузерной схемы ниже. Для resume сохраните ID, токен и исходные параметры приватно, не генерируйте
их заново. Проверки checksum частей и complete обязательны независимо от способа fingerprint.

**Ответ сервера:** HTTP `201 Created`, JSON состояния сессии, не JobDto.
При первом создании `state=UPLOADING`, `parts=[]`, `receivedBytes=0`, `sha256=null`, `jobId=null`.
Повтор создания с теми же ID, токеном и параметрами также возвращает 201 с текущим состоянием той же
сессии; другие параметры — 409. Токен сервер не возвращает.

JSON состояния включает `uploadId`, `state`, `filename`, `totalSize`, `chunkSize`, `partCount`,
`parts: [{index, sha256}]`, `receivedBytes`, `fingerprint`, параметры solver, времена Unix в миллисекундах,
`sha256` полного файла и `jobId` после COMPLETED. `fingerprint` — идентичность для resume, не обязательно
полный SHA файла. Браузер вычисляет её по имени, размеру, lastModified и хешам трёх образцов до 64 KiB
(начало, середина, конец). Сервер проверяет полные суммы частей и всего принятого файла отдельно.

### Передать части и продолжить

`PUT /api/v1/uploads/{id}/parts/{index}`, `application/octet-stream`, ответ `200` — обновлённое состояние.
Индекс начинается с нуля. Обязательны `X-Upload-Token` и `X-Part-SHA256` с SHA-256 бинарной части.
Длина должна соответствовать chunkSize либо остатку последней части; нельзя писать за пределы диапазона.

```bash
UPLOAD_ID='UUID сессии'
# UPLOAD_TOKEN хранится клиентом приватно; PART_SHA256 — хеш подготовленного part.bin.
curl -k --fail -X PUT \
  -H "X-Upload-Token: $UPLOAD_TOKEN" -H "X-Part-SHA256: $PART_SHA256" \
  -H 'Content-Type: application/octet-stream' --data-binary @part.bin \
  "$BASE/api/v1/uploads/$UPLOAD_ID/parts/0"
curl -k -H "X-Upload-Token: $UPLOAD_TOKEN" "$BASE/api/v1/uploads/$UPLOAD_ID"
```

GET возвращает подтверждённые части; клиент отправляет только отсутствующие. Повтор той же части
идемпотентен, конфликтующий checksum получает 409, неверный хеш полученных байтов — 422.
Подтверждение выдаётся после записи и синхронизации данных и атомарного сохранения журнала.

Браузер использует File.slice(), не загружая весь файл в ArrayBuffer. Одновременно передаются не более
двух частей по 16 MiB. При сетевой ошибке или HTTP 429/500/502/503/504 допускается пять попыток
с увеличением задержки, затем пауза. После reload нужно выбрать тот же файл: проверяются метаданные,
fingerprint и полные хеши подтверждённых частей. Потеря localStorage или токена лишает клиента
возможности продолжить защищённую сессию.

### Завершить или отменить

```bash
curl -k -X POST -H "X-Upload-Token: $UPLOAD_TOKEN" \
  "$BASE/api/v1/uploads/$UPLOAD_ID/complete"
# Отмена незавершённой передачи:
curl -k -X DELETE -H "X-Upload-Token: $UPLOAD_TOKEN" \
  "$BASE/api/v1/uploads/$UPLOAD_ID"
```

Complete возвращает `202` и состояние с `jobId`, а не JobDto. Сервер заново проверяет суммы,
переводит сессию UPLOADING → READY → COMPLETED и регистрирует ровно один закреплённый jobId.
Повтор complete возвращает тот же ID. Если job уже удалён — 410. Если очередь занята — 503,
полностью принятый файл сохраняется и complete можно повторить.

DELETE возвращает 204 для отменённой передачи; после передачи файла job — 409. Отмена upload
не является отменой выполняющегося solver. Сессия истекает после 24 часов неактивности;
ежечасная очистка удаляет временные данные, сохраняя зарегистрированный input job и небольшие журналы.

Прогресс upload переживает рестарт при сохранном volume. Расчёт прерванного job автоматически
не продолжается: после recovery он FAILED и требует явного rerun. Реализация рассчитана на один
экземпляр app, совместная запись нескольких процессов в один upload storage не предусмотрена.

## 6. Проверка, повтор и удаление

`POST /api/v1/validate`, multipart `input`, `result`, опционально `unknownRestrictionPolicy`:

```bash
curl -k -F 'input=@input.geojson' -F 'result=@result.geojson' "$BASE/api/v1/validate"
curl -k -X POST "$BASE/api/v1/jobs/$JOB_ID/rerun?mode=DEPTH"
curl -k -X DELETE "$BASE/api/v1/jobs/$JOB_ID"
```

Validate возвращает JSON `valid/errors/warnings/issues`; нарушения правил результата обычно выражены
этим отчётом с HTTP 200, ошибки запроса/непригодного входа — HTTP-кодом. Проверка и отчёты используют
общий лимит одного тяжёлого HTTP-анализа одновременно. Размер материализуемого output ограничен
`max(65536, Xmx/64)` байт; входной анализ также имеет отдельный бюджет. Это не ограничивает потоковое
скачивание большого результата или разрешённый размер самого upload.

Rerun требует `mode`, создаёт новый job с ответом 202 и сохраняет исходный. Входы переиспользуются через
hard links, а не полным копированием. DELETE разрешён для DONE/FAILED; QUEUED/RUNNING возвращают 409.
Повторное удаление отсутствующего job возвращает 204. Для остальных запросов отсутствующий job — 404.

## 7. Ошибки и доступ

Основная форма ошибки: `status`, `error`, `message`, при наличии — `diagnostics`.
Некоторые ответы upload filter/transport содержат только `status` и `message`; клиент не должен
требовать поле `error` во всех ответах. Stack trace внутренней ошибки наружу не выдаётся.

| HTTP | Типичный случай |
|---|---|
| 400 | Некорректный параметр, enum, multipart, JSON или структура запроса |
| 403 | Отсутствует или неверен секрет upload session |
| 404 | Не найдено задание, файл или upload session |
| 405 / 415 | Неподдерживаемый HTTP method / Content-Type |
| 409 | Результат ещё не готов, job активен при DELETE, конфликт части/метаданных, missing parts |
| 410 | Upload истёк/отменён либо завершённый job уже удалён |
| 413 | Превышен лимит файла или POST-метаданных |
| 422 | Некорректный вход с диагностикой или несовпадение checksum части |
| 503 | Очередь/анализ занят, превышен бюджет анализа, временная ошибка upload либо синхронный ресурсный отказ |
| 507 | Недостаточно места в хранилище |
| 500 | Внутренняя ошибка, в ответе безопасная ссылка на запись журнала |

Асинхронная ошибка solver, включая COMPLEXITY_LIMIT, читается из состояния и диагностики job;
`DONE` подтверждает завершение и валидацию возвращённых вариантов, но не подключение всех ОКС.
При исчерпании вычислительного бюджета может сохраниться проверенная сеть с частью подключений:
остальные ОКС перечислены в результате, учтены в штрафе и имеют причину `COMPLEXITY_LIMIT`.
Это не доказательство геометрической недостижимости. Если проверенных вариантов нет, job получает `FAILED`.
HTTP 200 при чтении FAILED job не означает успешный расчёт.

У job endpoints нет пользовательской авторизации и изоляции владельцев. UUID не является секретом,
особенно при доступном списке jobs. X-Upload-Token защищает только сессию передачи. Правила размещения
в доверенном контуре описаны в [deployment](deployment.md). Ограниченные очередь, размер файлов,
свободное место и конкурентный анализ сдерживают отдельные операции, но не заменяют контроль доступа.
