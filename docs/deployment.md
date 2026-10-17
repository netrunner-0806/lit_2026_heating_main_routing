# Развёртывание на сервер и настройка GitHub Actions

Инструкция по подготовке чистого сервера и настройке pipeline `.github/workflows/ci-cd.yml`.
Все значения в примерах (`heatnet.example.ru`, `203.0.113.10`, `deploy`, `/opt/heatnet`, `a1b2c3d`)
условные — подставьте свои. Настоящие пароли, токены и ключи в репозиторий и документацию не записываются.

## Как это устроено

Pipeline запускается на каждый push в `main` и вручную (Actions → CI/CD → Run workflow). Стадии:

1. **verify** — сначала определяет, затронуты ли файлы образа или деплоя: `src/`, `pom.xml`, `Dockerfile`,
   `.dockerignore`, `docker-compose.yml`, `compose.prod.yaml`, `caddy/`, `.github/workflows/`. Push, в котором
   изменилось только остальное (`README.md`, `docs/`, `scripts/`, `examples/`, `ТЗ/`), завершает verify
   успешно, а публикацию и деплой пропускает. Ручной запуск проверяет всё всегда. Затем по порядку:
   - статические проверки, которые только сообщают об ошибках и ничего не исправляют:
     - `actionlint` для workflow, вместе с ShellCheck для его `run`-скриптов;
     - `yamllint` для всех `*.yml` / `*.yaml`;
     - ShellCheck для `scripts/*.sh`: ошибки валят job, предупреждения выводятся в отчёт;
     - `docker compose config --quiet` для обоих Compose-файлов, плюс проверка, что `compose.prod.yaml`
       выбирает публикуемый образ и правильно реагирует на `HEATNET_IMAGE_TAG`;
     - `caddy validate` для `caddy/Caddyfile`;
     - поиск секретов (gitleaks);
     - `node --check` для JS;
     - компиляция Java-исходников с `javac -Xlint:all`: без тестов, предупреждения только в отчёт;
     - osv-scanner: известные уязвимости зависимостей, только отчёт;
   - фронтенд-тесты `node --test src/test/js/*.test.cjs`;
   - контроль, что проверки не изменили рабочее дерево.

   **Java-тесты в CI не запускаются** (`mvn test` / `mvn verify` отсутствуют): это намеренное решение
   из-за их стоимости. Запускайте их локально (см. `CLAUDE.md` / README, раздел «Тестирование»).
2. **build-and-publish** — собирает образ по существующему `Dockerfile` и публикует его в GHCR в пакет
   `heatnet` владельца репозитория:
   - `ghcr.io/netrunner-0806/heatnet:<короткий SHA>` — 7 символов, как GitHub показывает
     короткий хеш коммита (например `a1b2c3d`). Тег неизменяемый: повторный запуск того же коммита
     переиспользует опубликованный образ, а образ другого коммита с тем же тегом не перезаписывается;
   - `ghcr.io/netrunner-0806/heatnet:latest` — переносится на новый образ, только если коммит
     новее того, на который `latest` уже указывает (сравнение по полному SHA и истории Git). Запуск
     для более старого коммита, завершившийся позже, `latest` назад не откатывает.

   Полный SHA коммита хранится в метке образа `org.opencontainers.image.revision`, короткий —
   в `org.opencontainers.image.version`, адрес репозитория — в `org.opencontainers.image.source`.
3. **deploy** — выполняется **автоматически после каждого push в `main`**, прошедшего verify и публикацию,
   и при ручном запуске для `main` с включённым параметром **deploy** (по умолчанию включён). В обоих случаях
   деплой идёт, только если этот запуск продвинул `latest`: более старый запуск, завершившийся после более
   нового, деплой пропускает — его выполнит более новый. Ручной запуск с выключенным **deploy** только
   проверяет и публикует. По SSH копирует на сервер `compose.prod.yaml` и `caddy/Caddyfile`, выполняет
   `docker compose -f compose.prod.yaml pull` и `docker compose -f compose.prod.yaml up -d --no-build --remove-orphans`,
   проверяет health контейнеров, запущенный образ и HTTP-доступность. На сервере ничего не собирается.

Пока secrets и variables из раздела 10 не созданы, job deploy после каждого push завершается ошибкой
`Missing deployment settings: ...` (до подключения к серверу). Образ при этом уже опубликован.

Какой тег приложения запускать, решает **только серверный `.env`** (переменная `HEATNET_IMAGE_TAG`,
см. раздел 7). CI эту переменную не передаёт и `.env` не создаёт, не читает в лог, не перезаписывает
и не удаляет.

## 1. Требования к серверу

- 64-битный Linux, на который официально ставится Docker Engine (далее команды для Ubuntu 24.04 / 26.04 LTS).
- Ресурсы: значения по умолчанию рассчитаны на 8 CPU / 16 ГБ RAM (`JAVA_OPTS` с `-Xmx13g`,
  см. `.env.example`). На сервере с меньшим объёмом памяти уменьшите `-Xmx` в `JAVA_OPTS`.
- Docker Engine и плагин Docker Compose v2 (команда `docker compose`). Workflow вызывает именно
  `docker compose`, отдельный `docker-compose` v1 на сервере не используется.
- Публичный IPv4 (и при желании IPv6), доменное имя, доступ по SSH.
- Свободное место на диске для образов и томов (база данных, файлы заданий в `heatnet-data`).

## 2. Установка Docker Engine и Compose v2

По официальной инструкции <https://docs.docker.com/engine/install/ubuntu/> (на сервере, под администратором):

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

Проверка:

```bash
sudo systemctl enable --now docker
docker --version
docker compose version        # должно напечатать "Docker Compose version v2..." или новее
sudo docker run --rm hello-world
```

Если ставите другим способом — сверяйтесь с официальной документацией Docker для вашего дистрибутива.

## 3. Пользователь для деплоя

Отдельный пользователь без пароля, вход только по ключу:

```bash
sudo adduser --disabled-password --gecos "" deploy
sudo usermod -aG docker deploy
```

Членство в группе `docker` нужно, чтобы выполнять `docker compose` без `sudo`.
**Важно:** группа `docker` фактически равна root-доступу к серверу, поэтому ключ этого пользователя
выдаётся только GitHub Actions и хранится только в GitHub Secrets. `sudo` пользователю `deploy` не нужен.

Проверка (после повторного входа пользователя группа применяется):

```bash
sudo -iu deploy docker ps
```

## 4. SSH-доступ для GitHub Actions

### 4.1. Ключ для Actions

Создайте отдельную пару ключей на своём компьютере (не на сервере и не переиспользуя личный ключ).
Пароль ключу не задаётся, потому что CI вводить его не может:

```bash
ssh-keygen -t ed25519 -C "github-actions-deploy heatnet" -N "" -f ./heatnet_deploy
```

Получатся `heatnet_deploy` (приватный → секрет `SSH_PRIVATE_KEY`) и `heatnet_deploy.pub` (публичный → сервер).

### 4.2. Публичный ключ на сервер

```bash
sudo install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
sudo tee -a /home/deploy/.ssh/authorized_keys < heatnet_deploy.pub    # содержимое .pub скопируйте на сервер любым безопасным способом
sudo chown deploy:deploy /home/deploy/.ssh/authorized_keys
sudo chmod 600 /home/deploy/.ssh/authorized_keys
```

Опционально строку ключа в `authorized_keys` можно начать с `restrict ` (запрет пробросов портов, агента
и PTY): workflow выполняет только команды и `scp`. Если включите — проверьте вход по разделу 11.2.

Вход по паролю для сервера в целом лучше отключить (`PasswordAuthentication no` в `/etc/ssh/sshd_config`),
предварительно убедившись, что вход по ключу работает.

### 4.3. `SSH_KNOWN_HOSTS` с проверкой отпечатка

Workflow подключается со `StrictHostKeyChecking yes`: ключ сервера должен быть заранее известен.
Отключать эту проверку нельзя — иначе секреты и управление сервером можно отдать подменённому хосту.

1. На сервере через доверенный канал (консоль провайдера или уже проверенная SSH-сессия) посмотрите отпечаток:

   ```bash
   ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
   ```

2. На своём компьютере получите ключ хоста **по тому же имени или IP и порту, что будут в `SSH_HOST` / `SSH_PORT`**:

   ```bash
   ssh-keyscan -p 22 -t ed25519 203.0.113.10 > known_hosts_prod
   ssh-keygen -lf known_hosts_prod
   ```

3. Сравните отпечатки (`SHA256:...`) из пунктов 1 и 2. Если совпадают — содержимое `known_hosts_prod`
   и есть значение секрета `SSH_KNOWN_HOSTS`. При нестандартном порте строка начинается с `[203.0.113.10]:2222` —
   так и должно быть.

Если `SSH_HOST` — доменное имя, сканируйте именно домен. Если сервер переустановлен и ключ хоста сменился,
повторите процедуру и обновите секрет.

## 5. Каталог проекта на сервере

```bash
sudo install -d -m 750 -o deploy -g deploy /opt/heatnet
```

Путь (`/opt/heatnet`) — значение секрета `DEPLOY_PATH`. Содержимое каталога:

| Файл | Кто создаёт | Назначение |
|---|---|---|
| `.env` | вы, вручную (раздел 6) | настройки приложения, БД, Caddy и выбор тега образа; CI его не трогает |
| `compose.prod.yaml` | CI при каждом деплое | описание production-стека |
| `caddy/Caddyfile` | CI при каждом деплое (перезаписывается, только если изменился) | конфигурация reverse proxy |
| `.compose.prod.yaml.upload`, `caddy/.Caddyfile.upload`, `.caddy-reload` | CI, временно | служебные файлы загрузки и признак перезагрузки Caddy |

Данные хранятся не в каталоге, а в именованных томах Docker с фиксированным префиксом проекта `heatnet`:
`heatnet_heatnet-pg` (PostgreSQL), `heatnet_heatnet-data` (файлы заданий), `heatnet_heatnet-tmp`,
`heatnet_caddy-data` (сертификаты), `heatnet_caddy-config`.
**Никогда не выполняйте `docker compose down -v`** — это удалит базу и сертификаты.

При переходе с существующего `docker-compose.yml` сначала проверьте имя проекта:

```bash
docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' heatnet-app heatnet-db
```

Если оно отличается от `heatnet`, задайте **это существующее имя** в серверном `.env` как
`COMPOSE_PROJECT_NAME`. Оно имеет приоритет над `name: heatnet` и сохраняет выбор прежних томов.
Workflow прерывает развёртывание до `pull/up`, если обнаруживает несовпадение; автоматической
миграции, переименования или удаления томов нет.

## 6. Файл `.env`

Создаётся вручную пользователем `deploy`, права только для владельца:

```bash
sudo -iu deploy
cd /opt/heatnet
install -m 600 /dev/null .env
nano .env
```

Переменные, которые реально читает `compose.prod.yaml` (других он не передаёт в контейнеры):

| Переменная | Куда идёт | Обязательность | Значение по умолчанию | Пример формата |
|---|---|---|---|---|
| `COMPOSE_PROJECT_NAME` | Compose, префикс существующих томов и сетей | при переходе с другого имени проекта | `heatnet` из `name` | имя из `docker inspect`, см. раздел 5 |
| `POSTGRES_PASSWORD` | `db` (пароль при создании БД), `app` (`SPRING_DATASOURCE_PASSWORD`) | **задать обязательно** (умолчание `heatnet` небезопасно) | `heatnet` | `POSTGRES_PASSWORD=<случайная строка>` |
| `POSTGRES_USER` | `db`, `app` (`SPRING_DATASOURCE_USERNAME`), healthcheck `db` | необязательна | `heatnet` | `POSTGRES_USER=heatnet` |
| `POSTGRES_DB` | `db`, `app` (`SPRING_DATASOURCE_URL`), healthcheck `db` | необязательна | `heatnet` | `POSTGRES_DB=heatnet` |
| `DOMAIN` | `caddy` (адрес сайта в Caddyfile) | **задать обязательно** для production (иначе `localhost` с самоподписанным сертификатом) | `localhost` | `DOMAIN=heatnet.example.ru` |
| `ACME_EMAIL` | `caddy` (учётная запись Let's Encrypt) | **рекомендуется задать** реальный адрес | `admin@example.com` | `ACME_EMAIL=ops@example.ru` |
| `HEATNET_WORKERS` | `app`, число параллельных расчётов | необязательна | `1` | `HEATNET_WORKERS=1` |
| `HEATNET_MAX_VARIANTS` | `app`, максимум вариантов на задание (1..3) | необязательна | `3` | `HEATNET_MAX_VARIANTS=3` |
| `JAVA_OPTS` | `app`, параметры JVM | необязательна; уменьшите `-Xmx` на сервере с RAM < 16 ГБ | `-Xms1g -Xmx13g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError` | `JAVA_OPTS='-Xms1g -Xmx6g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError'` |
| `HEATNET_IMAGE_TAG` | тег образа `app` | необязательна: нет или пусто → `latest` | `latest` | `HEATNET_IMAGE_TAG=a1b2c3d` |

Замечания:

- `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` применяются образом PostgreSQL **только при первой
  инициализации** тома `heatnet_heatnet-pg`. Поменять пароль потом правкой `.env` нельзя: сначала смените
  его в самой БД (`ALTER USER ... PASSWORD ...`), затем в `.env`.
- Пароль удобно сгенерировать без спецсимволов, которые требуют экранирования в `.env`: `openssl rand -hex 24`.
- `DOMAIN` — имя без схемы. Значение вида `http://...` отключает HTTPS в Caddy (подходит только для локальной проверки).
- `HEATNET_TMP_DIR`, `SPRING_DATASOURCE_*` задаются в `compose.prod.yaml` и в `.env` не нужны.
  Прочие переменные из `application.yml` (например `HEATNET_IMPROVE`) `compose.prod.yaml` в контейнер
  не передаёт, поэтому в `.env` они ни на что не влияют.

Пример `.env` (значения условные):

```dotenv
POSTGRES_DB=heatnet
POSTGRES_USER=heatnet
POSTGRES_PASSWORD=<сгенерированный пароль>
HEATNET_WORKERS=1
HEATNET_MAX_VARIANTS=3
JAVA_OPTS='-Xms1g -Xmx13g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError'
DOMAIN=heatnet.example.ru
ACME_EMAIL=ops@example.ru
# HEATNET_IMAGE_TAG не задан: production следует за latest
```

## 7. Выбор версии: `latest` или конкретный тег

В `compose.prod.yaml`:

```yaml
image: ghcr.io/netrunner-0806/heatnet:${HEATNET_IMAGE_TAG:-latest}
```

### Режим 1. Автоматически `latest`

В `.env` нет строки `HEATNET_IMAGE_TAG` (или она пустая: `HEATNET_IMAGE_TAG=`). Каждый успешный
деплой (автоматически после push в `main`) скачивает текущий `latest` и пересоздаёт `app`, если образ изменился.

### Режим 2. Закрепление на конкретной версии

```dotenv
HEATNET_IMAGE_TAG=a1b2c3d
```

Production запускает `ghcr.io/netrunner-0806/heatnet:a1b2c3d` независимо от новых публикаций.
Pipeline продолжает публиковать новые образы и двигать `latest`, а deploy на сервере по-прежнему обновляет
`compose.prod.yaml`/Caddyfile и запускает `pull`/`up`, но приложение остаётся на закреплённом теге.
В сводке запуска появится предупреждение `Production is pinned`.

### Возврат к `latest`

Удалите строку `HEATNET_IMAGE_TAG` из `.env` (или оставьте пустое значение) и примените (раздел 12)
либо дождитесь следующего деплоя.

Переменная действует только из `.env`: workflow её не передаёт и перед командами Compose явно сбрасывает
одноимённую переменную окружения удалённой оболочки, чтобы она не перекрыла `.env`.
Для ручных команд на сервере не экспортируйте `HEATNET_IMAGE_TAG` в профиле оболочки по той же причине.

### Переход со старого имени образа

Раньше образ публиковался как `ghcr.io/netrunner-0806/lit_2026_heating_main_routing`; теперь — `ghcr.io/netrunner-0806/heatnet`.
Теги старого пакета в новый не переносятся. Если в серверном `.env` закреплён тег (`HEATNET_IMAGE_TAG=...`),
до первого деплоя нового образа уберите строку или замените значение на тег, уже опубликованный в пакете `heatnet`,
иначе шаг `Pull and update the stack` остановится на ошибке `docker compose pull failed`. Тома, контейнеры и
имя Compose-проекта (`heatnet`) от смены имени образа не меняются: при деплое `app` пересоздаётся из нового образа.
Старый пакет workflow не трогает; удалить его можно вручную на странице пакета, когда он больше не нужен для отката.

## 8. Пакет в GHCR: публичный доступ (рекомендуется) или токен на чтение

Пакет `heatnet` создаётся первым успешным запуском `build-and-publish` в пространстве владельца репозитория
(`netrunner-0806`) и автоматически связывается с репозиторием (метка `org.opencontainers.image.source`, публикация
из workflow этого репозитория): он появляется в блоке **Packages** на странице репозитория. Новый пакет GHCR
по умолчанию **приватный** — даже у публичного репозитория.

### 8.1. Публичный пакет (выбранный вариант)

Сервер скачивает публичный образ без входа в реестр, поэтому `GHCR_READ_USER` и `GHCR_READ_TOKEN` не нужны:
не создавайте их (или удалите), и deploy выполнит `pull` анонимно.

Сделать пакет публичным можно только после его первой публикации и только с правами администратора пакета
(для пакета пользователя — его владелец `netrunner-0806`):

1. Профиль `netrunner-0806` → вкладка **Packages** → `heatnet` (или страница репозитория → Packages → `heatnet`).
2. **Package settings** (шестерёнка справа).
3. Внизу, в **Danger Zone** → **Change visibility** → **Public**.
4. Введите имя пакета `heatnet` и подтвердите **I understand the consequences, change package visibility**.

**Это необратимо:** по документации GitHub публичный пакет нельзя снова сделать приватным. В образе только
собранное приложение из публичного репозитория (`Dockerfile` копирует `pom.xml` и `src/`); серверный `.env`
и секреты в образ не попадают.

Порядок первого запуска: первый push публикует образ, пока пакет ещё приватный, поэтому deploy этого запуска
без токена упадёт на `docker compose pull failed`. Переключите пакет в Public и перезапустите упавший job
(Actions → запуск → **Re-run failed jobs**) или запустите workflow вручную.

### 8.2. Приватный пакет: токен на чтение

Если пакет остаётся приватным, задайте **оба** секрета; если задан только один, deploy остановится с ошибкой.
GitHub Packages принимает для `docker login` только **personal access token (classic)**; fine-grained токены
для GHCR не поддерживаются. Для скачивания достаточно scope `read:packages`.

1. GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token (classic).
2. Note: например `heatnet-server-ghcr-read`; Expiration: ограниченный срок (поставьте напоминание о продлении).
3. Scopes: **только `read:packages`**. Не выбирайте `write:packages`, `delete:packages`, `repo`.
4. Значение токена → секрет `GHCR_READ_TOKEN`; логин владельца токена → секрет `GHCR_READ_USER`.

Classic-токен с `read:packages` даёт чтение всех пакетов, доступных его владельцу. Если это недопустимо,
создайте отдельную служебную учётную запись GitHub, выдайте ей доступ на чтение только к этому пакету
и выпустите токен от её имени.

С заданными секретами workflow логинится на сервере перед `pull` (токен передаётся через stdin, в логи
не попадает) и всегда выполняет `docker logout ghcr.io` после деплоя. Постоянно хранить учётные данные
на сервере не нужно; для ручного `pull` логиньтесь временно (раздел 12).

### 8.3. Публикация

Публикация из Actions идёт через встроенный `GITHUB_TOKEN` (`packages: write`), создавать для неё ничего
не нужно. Если пакет `heatnet` уже существовал до этого workflow и не связан с репозиторием,
`GITHUB_TOKEN` не сможет в него писать: на странице пакета (Package settings → Manage Actions access)
добавьте репозиторий с ролью Write.

## 9. Сеть, DNS, домен, reverse proxy

- **DNS:** A-запись (и AAAA, если на сервере рабочий IPv6) для `DOMAIN` → IP сервера. Создайте её **до первого
  запуска**: Caddy сразу запрашивает сертификат Let's Encrypt, а частые неудачные попытки упираются в лимиты.
- **Входящие порты:** `80/tcp` и `443/tcp` (HTTP-проверка ACME и HTTPS), `443/udp` (HTTP/3), порт SSH.
  GitHub-hosted runners приходят с большого и меняющегося набора адресов, поэтому ограничить SSH по IP
  runner'а практически нельзя — полагайтесь на вход только по ключу.
- **Reverse proxy:** контейнер `caddy` (`caddy:2.11.7`) с `caddy/Caddyfile` из репозитория; принимает 80/443
  и проксирует на `app:8080`. Порты `app` (8080) и `db` (5432) наружу не публикуются, `db` находится
  во внутренней сети `backend`.
- **Firewall:** порты, опубликованные Docker, обходят правила `ufw`. Наружу публикуются только 80/443 Caddy,
  поэтому это ожидаемо, но не рассчитывайте закрыть их через `ufw`.
- **`PRODUCTION_URL`** (`https://heatnet.example.ru`) должен быть доступен из интернета: post-deploy проверка
  выполняется с runner'а GitHub.

## 10. Настройка GitHub Actions

### 10.1. Secrets и Variables

Точные имена из workflow:

| Имя | Тип | Что это | Откуда взять / как сформировать | Обязателен | Где добавить (раздел 10.2) |
|---|---|---|---|---|---|
| `SSH_HOST` | Secret | адрес сервера | IP или DNS-имя сервера; то же имя, что использовалось в `ssh-keyscan` | да | Environment secrets `production` или Repository secrets |
| `SSH_PORT` | Secret | порт SSH | порт `sshd` на сервере | нет (по умолчанию `22`) | там же |
| `SSH_USER` | Secret | пользователь для деплоя | `deploy` из раздела 3 | да | там же |
| `SSH_PRIVATE_KEY` | Secret | приватный ключ для входа | полное содержимое файла `heatnet_deploy` из раздела 4.1, включая строки `-----BEGIN/END OPENSSH PRIVATE KEY-----` | да | там же |
| `SSH_KNOWN_HOSTS` | Secret | ключ хоста сервера | содержимое `known_hosts_prod` после сверки отпечатка (раздел 4.3) | да | там же |
| `DEPLOY_PATH` | Secret | каталог проекта на сервере | абсолютный путь из раздела 5, например `/opt/heatnet` | да | там же |
| `GHCR_READ_USER` | Secret | логин для `docker login ghcr.io` на сервере | логин владельца токена (раздел 8.2) | нет: только для приватного пакета, вместе с `GHCR_READ_TOKEN` | там же |
| `GHCR_READ_TOKEN` | Secret | токен для скачивания образа | classic PAT только с `read:packages` (раздел 8.2) | нет: только для приватного пакета, вместе с `GHCR_READ_USER` | там же |
| `PRODUCTION_URL` | Variable | публичный адрес для HTTP-проверок | `https://` + `DOMAIN`, без завершающего `/` | да | Environment variables `production` или Repository variables |
| `GITHUB_TOKEN` | создаётся автоматически | публикация в GHCR, GitHub API | ничего создавать не нужно; права заданы в workflow (`packages: write` только у build-and-publish) | — | — |

Имя образа (`IMAGE_NAME: heatnet`) и владелец (`github.repository_owner`) заданы в самом workflow — отдельных
secrets/variables для них нет.

`verify` и `build-and-publish` никаких пользовательских секретов не используют. Если обязательной
записи не хватает, deploy сразу падает с сообщением `Missing deployment settings: ...` и перечнем имён.

### 10.2. Куда их создавать

Job `deploy` объявляет `environment: production`. Workflow читает `secrets.*` и `vars.*`, поэтому значения
работают с любого уровня: из окружения `production`, если они там заданы, иначе из репозитория.
Если одно имя задано на обоих уровнях, используется значение окружения — не дублируйте.

Создавать secrets, variables и environments может только пользователь с правами **администратора**
репозитория (владелец `netrunner-0806` или выданная им роль Admin); права Write для этого недостаточно.
Окружение `production` создаётся автоматически при первом запуске job'а `deploy`, но настроить его
заранее удобнее вручную: Settings → Environments → New environment → `production`.

Ограничения тарифов GitHub (по документации
[Deployments and environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)):

| Возможность | Публичный репозиторий | Приватный репозиторий |
|---|---|---|
| Environment secrets | любой тариф, включая Free | GitHub Pro, Team или Enterprise |
| Environment variables, Deployment branches and tags | любой тариф, включая Free | GitHub Pro или Team (и Enterprise) |
| Required reviewers, wait timer | любой тариф, включая Free | на Free, Pro и Team недоступны |

Репозиторий `netrunner-0806/lit_2026_heating_main_routing` сейчас **публичный**, поэтому рекомендуемый вариант
доступен на Free:

1. Settings → Environments → `production` → **Deployment branches and tags** → Selected branches and tags →
   добавить правило `main`.
2. В том же окружении: секреты `SSH_*` и `DEPLOY_PATH` (для приватного пакета ещё `GHCR_READ_*`) — в **Environment secrets**, `PRODUCTION_URL` — в **Environment variables**.
   Тогда SSH- и GHCR-секреты доступны только job'у `deploy` и только из `main`.
3. Required reviewers не включайте: деплой согласован как автоматический, а с reviewers каждый push будет
   ждать ручного подтверждения.

Упрощённый вариант (обязателен, если репозиторий станет приватным на GitHub Free): всё в Settings →
Secrets and variables → Actions — секреты `SSH_*` и `DEPLOY_PATH` (для приватного пакета ещё `GHCR_READ_*`) во вкладке Secrets → **Repository secrets**,
`PRODUCTION_URL` — во вкладке Variables → **Repository variables**.

### 10.3. После настройки секретов

Локальные копии `heatnet_deploy` (приватный ключ) и токена удалите или храните только в менеджере паролей.

## 11. Первый запуск и проверка

### 11.1. До первого запуска pipeline

На сервере должны быть: Docker и Compose v2 (раздел 2), пользователь `deploy` в группе `docker` (3),
публичный ключ в `authorized_keys` (4.2), каталог `DEPLOY_PATH` (5), `.env` (6), DNS-запись и открытые
порты (9). В GitHub — все записи из раздела 10.1.

### 11.2. Доступ по SSH тем же ключом и `known_hosts`, что у Actions

С вашего компьютера:

```bash
ssh -i ./heatnet_deploy -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=./known_hosts_prod -p 22 deploy@203.0.113.10 'docker compose version && test -f /opt/heatnet/.env && echo READY'
```

Ожидается версия Compose и `READY`. Ошибка `Host key verification failed` означает неверный `known_hosts`.

### 11.3. Доступ к GHCR с сервера

Публичный пакет (раздел 8.1), под `deploy` на сервере — без входа в реестр:

```bash
docker pull ghcr.io/netrunner-0806/heatnet:latest
```

Ответ `denied` / `unauthorized` значит, что пакет ещё приватный; до первой публикации — `not found`.

Приватный пакет (раздел 8.2) — с токеном (вводится в ответ на запрос пароля и в истории команд не остаётся):

```bash
docker login ghcr.io -u <GHCR_READ_USER>
docker pull ghcr.io/netrunner-0806/heatnet:latest
docker logout ghcr.io
```

До первой публикации образа `pull` ответит `not found` — сама авторизация при этом проверена успешным `Login Succeeded`.

### 11.4. Проверка Compose-конфигурации без изменений

Команды только читают конфигурацию. `compose.prod.yaml` и `caddy/Caddyfile` CI положит при первом деплое;
для проверки заранее скопируйте их из репозитория в `DEPLOY_PATH` с теми же путями (CI потом их перезапишет).

```bash
cd /opt/heatnet
docker compose -f compose.prod.yaml config --quiet && echo "compose OK"
docker compose -f compose.prod.yaml config --images
```

Вторая команда показывает выбранные образы, в том числе тег `app` (`latest` или значение `HEATNET_IMAGE_TAG`).
Не запускайте `docker compose config` без `--quiet` там, где вывод могут увидеть другие: он печатает
подставленные значения, включая `POSTGRES_PASSWORD`.

### 11.5. Запуск pipeline и логи

- Push в `main` с изменениями приложения, сборки или деплоя выполняет проверки, публикацию образа и деплой.
- Ручной запуск: Actions → **CI/CD** → **Run workflow** → Branch `main` → параметр **deploy** (по умолчанию
  включён) → **Run workflow**. С выключенным **deploy** запуск ограничивается проверками и публикацией.
  Ручной запуск для другой ветки проверяет код и публикует образ с её коротким SHA, но `latest` не двигает
  и не деплоит.
- Из терминала: `gh workflow run ci-cd.yml --ref main` (с деплоем) или
  `gh workflow run ci-cd.yml --ref main -f deploy=false` (без деплоя).
- Логи: вкладка Actions → запуск → job (`verify`, `build-and-publish`, `deploy`) → шаг. В Summary запуска —
  опубликованный тег, решение по `latest`, запущенный образ и полный SHA.
- Из терминала (GitHub CLI):

  ```bash
  gh run list --workflow ci-cd.yml
  gh run watch
  gh run view --log-failed
  ```

### 11.6. Контейнеры, health и HTTP

На сервере:

```bash
cd /opt/heatnet
docker compose -f compose.prod.yaml ps
docker inspect -f '{{.State.Health.Status}}' heatnet-db heatnet-app
docker compose -f compose.prod.yaml logs --tail 200 app
```

Снаружи:

```bash
curl -fsS https://heatnet.example.ru/actuator/health
curl -sS -o /dev/null -w '%{http_code}\n' https://heatnet.example.ru/
```

Ожидается `"status":"UP"` и `200`.

### 11.7. Какой образ и какой полный SHA запущены

```bash
docker inspect -f '{{.Config.Image}}' heatnet-app
docker inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' heatnet-app
docker inspect -f '{{index .Config.Labels "org.opencontainers.image.version"}}' heatnet-app
```

Первая строка — тег из Compose (`...:latest` или `...:a1b2c3d`), вторая — полный SHA коммита, третья — короткий тег, под которым образ был опубликован.
Коммит открывается по адресу `https://github.com/netrunner-0806/lit_2026_heating_main_routing/commit/<полный SHA>`.

## 12. Откат и возврат к `latest`

Опубликованные теги видны на странице пакета (профиль `netrunner-0806` или страница репозитория → Packages → `heatnet`)
и в Summary запусков (`Published image`).

**Откат на ранее опубликованный тег:**

1. (Рекомендуется) резервная копия БД — операция только читает базу:

   ```bash
   docker exec heatnet-db pg_dump -U heatnet heatnet > ~/heatnet-$(date +%F-%H%M).sql
   ```

   Схема обновляется Hibernate (`ddl-auto: update`) только добавлением; старая версия обычно работает
   с более новой схемой, но это не гарантировано.
2. В `/opt/heatnet/.env` укажите `HEATNET_IMAGE_TAG=a1b2c3d`.
3. Примените:

   ```bash
   cd /opt/heatnet
   docker login ghcr.io -u <GHCR_READ_USER>     # только для приватного пакета
   docker compose -f compose.prod.yaml pull app
   docker compose -f compose.prod.yaml up -d --no-build --remove-orphans
   docker logout ghcr.io                        # только для приватного пакета
   ```

4. Проверьте по разделам 11.6–11.7.

Вместо шага 3 можно запустить workflow вручную: deploy применит закреплённый тег и проверит его, но перед
этим пройдут тесты и сборка.

**Возврат к `latest`:** удалите `HEATNET_IMAGE_TAG` из `.env` (или сделайте значение пустым) и выполните
те же команды шага 3 — будет запущен текущий `latest`.

## 13. Типичные ошибки

| Симптом (шаг workflow) | Причина | Что сделать |
|---|---|---|
| verify падает на `actionlint`, `yamllint`, `ShellCheck`, `Docker Compose and Caddyfile configuration` | ошибка в workflow, YAML, shell-скрипте, Compose-файле или Caddyfile; линтеры ничего не исправляют сами | открыть шаг, исправить указанную строку; локально — `docker compose -f compose.prod.yaml config --quiet`, `node --test src/test/js/*.test.cjs` |
| `app image does not resolve to ghcr.io/<owner>/heatnet:latest` (verify) | имя образа в `compose.prod.yaml` не совпадает с `IMAGE_NAME` и владельцем репозитория в workflow | привести `image:` к `ghcr.io/<owner>/heatnet:${HEATNET_IMAGE_TAG:-latest}` |
| `Checks did not modify the checkout` падает | какая-то проверка создала или изменила файлы | список файлов есть в логе шага; проверка не должна писать в рабочее дерево |
| deploy в статусе Skipped | push изменил только документацию; ручной запуск с выключенным **deploy** или не для `main`; этот запуск не продвинул `latest` (более новый уже опубликован и деплоит сам) | причина видна в Summary (`Pipeline:` в verify, `latest not moved` в build-and-publish) |
| `denied` / `unauthorized` при push в GHCR (build-and-publish) | пакет `heatnet` уже существует и не связан с репозиторием, либо у `GITHUB_TOKEN` нет прав на него | Package settings → Manage Actions access → добавить репозиторий с ролью Write (раздел 8) |
| `Missing deployment settings: ...` (Check deployment settings) | не создан секрет или переменная, либо создан не на том уровне | создать записи с точными именами (раздел 10) |
| `Permission denied (publickey)` | публичный ключ не в `authorized_keys`, неверные права на `~/.ssh`, неверный `SSH_USER` или ключ в секрете обрезан | раздел 4.2; права `700` на `.ssh` и `600` на `authorized_keys`; ключ копировать целиком |
| `Connection timed out` / `Connection refused` | неверный `SSH_HOST`/`SSH_PORT`, порт закрыт firewall'ом или провайдером | проверить адрес, порт и правила firewall |
| `Host key verification failed` | `SSH_KNOWN_HOSTS` не соответствует серверу, имени или порту из `SSH_HOST`/`SSH_PORT` | повторить раздел 4.3 со сверкой отпечатка; проверку не отключать |
| `.env not found on the server` | файла нет в `DEPLOY_PATH` или путь в секрете другой | создать `.env` вручную (раздел 6) |
| `docker compose pull failed` / `denied` без `GHCR_READ_*` | пакет `heatnet` ещё приватный (сразу после первой публикации это так) | сделать пакет публичным (раздел 8.1) и перезапустить job, либо задать оба секрета (8.2) |
| `Set both GHCR_READ_USER and GHCR_READ_TOKEN ... or neither` | задан только один из двух GHCR-секретов | удалить оба (публичный пакет) или задать оба (приватный) |
| `unauthorized` / `denied` при `docker login` или `pull` с токеном | неверный `GHCR_READ_USER`, токен истёк, не classic или без `read:packages`, у владельца нет доступа к пакету | выпустить новый classic PAT с `read:packages` и обновить секреты |
| `docker compose pull failed. Check that ...:<tag> exists` | в `HEATNET_IMAGE_TAG` опечатка или тег не опубликован | сверить тег со списком версий пакета или убрать переменную, чтобы вернуться к `latest` |
| `The app image in compose.prod.yaml does not resolve to ...` | имя образа в `compose.prod.yaml` не совпадает с репозиторием | имя образа должно быть `ghcr.io/<owner>/<repo>` в нижнем регистре |
| `Short tag collision` (build-and-publish) | другой коммит начинается с тех же 7 символов или тег в GHCR принадлежит другому коммиту | публикация остановлена, ничего не перезаписано; сделайте новый коммит (у него будет другой SHA) |
| `Containers did not become healthy` | приложение не стартует: нет связи с БД, неверный пароль БД, нехватка памяти | смотреть вывод `logs app` в шаге; проверить `POSTGRES_*` и `JAVA_OPTS` |
| `heatnet-app runs ..., expected ...` / `was not recreated from the freshly pulled` | контейнер не пересоздан из нового образа | на сервере `docker compose -f compose.prod.yaml up -d --no-build --remove-orphans`, затем разобраться по логам |
| `Production is pinned` (предупреждение) | в `.env` задан `HEATNET_IMAGE_TAG` | это не ошибка; чтобы следовать `latest`, уберите переменную (раздел 7) |
| `curl` падает в Check application availability | DNS ещё не указывает на сервер, сертификат не выпущен, закрыты 80/443, `DOMAIN` не совпадает с `PRODUCTION_URL` | `docker compose -f compose.prod.yaml logs caddy`; проверить DNS и порты (раздел 9) |

## Ссылки

- Docker Engine на Ubuntu: <https://docs.docker.com/engine/install/ubuntu/>
- Docker Compose, переменные и `.env`: <https://docs.docker.com/compose/how-tos/environment-variables/>
- Работа с Container registry (GHCR): <https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry>
- Deployments and environments: <https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments>
- Secrets в GitHub Actions: <https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets>
