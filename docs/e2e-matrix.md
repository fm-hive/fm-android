# Протоколы и API: матрица проверки Android-клиента (#30)

Документ фиксирует, что из `docs/api` и `docs/proto` реализовано в
`fm-android`, какими тестами это покрыто и что нужно проверить на стенде.
Статусы:

- **auto** — есть unit/integration-тест в `app/src/test`;
- **manual** — проверяется на эмуляторе/стенде;
- **node** — поведение узла, клиент только отправляет сообщение или читает
  результат;
- **gap** — в клиенте отсутствует или требует доработки.

## API (`docs/api`)

| Документ | Клиент | Покрытие | Статус |
| --- | --- | --- | --- |
| `policy.md` | `PolicyClient`, `NetworkManager.register/resign`, auto-resign | `PolicyClientTest`, `NetworkManagerTest` | auto |
| `identity.md` | `NetworkSession.identity`, `setName` | `NetworkSessionTest` | auto |
| `send_receive.md` | `NetworkSession.sync/sendMessages`, outbox, ACK | `NetworkSessionTest` | auto |
| `service.md` | `requestPublicKey`, `respondToPubkeyRequest`, netmail pubkey_request/response | `NetworkSessionTest` | auto |
| `subscribe_echo.md` | `setSubscribed`, `acceptRules` | `NetworkSessionTest` | auto |
| `unsubscribe_echo.md` | `setSubscribed(false)` | `NetworkSessionTest` | auto |
| `create_local_echo.md` | `createLocalEcho` | `NetworkSessionTest` | auto |
| `create_echo_with_rules.md` | `createEchoWithRules` | `NetworkSessionTest` | auto |
| `voucher.md` | create/list/revoke, QR invite | `NetworkSessionTest` | auto |
| `merge.md` | start; further phases run on the node | `NetworkSessionTest` | node |
| `recover.md` | `NetworkManager.recover` | `NetworkManagerTest` | auto |
| `refill.md` | request + reaction response | `NetworkSessionTest`, `ActiveProtocolsList` | auto/manual |
| `client_synchronize.md` | append/read/delete, blog ops | `NetworkSessionTest` | auto |

## Протоколы (`docs/proto`)

| Документ | Клиент | Покрытие | Статус |
| --- | --- | --- | --- |
| `vote.md` | poster, vote, result, ballot | `VoteMappingTest`, `NetworkSessionTest` | auto |
| `election.md` | start election, vote/retract, candidate card | `NetworkSessionTest`, `ActiveProtocolsList` | auto/manual |
| `excommunicate.md` | admin proposal in Admin council | `AdminScreen` | manual (PASS) |
| `change_policy.md` | policy editor, chunk diff, diff preview | `PolicyParamsTest`, `AdminScreen` | auto/manual (PASS) |
| `change_rules.md` | rules editor, rename proposal | `NetworkSessionTest`, `RulesEditor` | auto/manual |
| `remove_echo.md` | moderators proposal dialog | `AdminScreen`, `RemoveEchoPayloadTest` | auto/manual (PASS) |
| `merge.md` | QR invite + start; phases on the node | `NetworkSessionTest` | node |
| `conflict.md` | passive protocol list | `ProtocolsScreen` | manual |
| `recover.md` | recovery screen + signed request | `NetworkManagerTest` | auto/manual |
| `refill.md` | request dialog + respond with 👍 | `NetworkSessionTest` | auto/manual |
| `appeal.md` | admin appeal dialog with punishment bundle | `NetworkSessionTest`, `ModerationMappingTest` | auto/manual |
| `remote_subscribe.md` | request from the echo browser | `NetworkSessionTest` | auto/manual |
| `announcements.md` | `announcement` compose toggle when rules allow | `NetworkSessionTest`, `EchoPermissionsTest` | auto |
| `polling.md` | node-side; client uses the periodic `send_receive` loop | — | node |
| `echomail_router.md` | node-side backbone | — | node |
| `routing.md`, `slowdown.md` | node-side | — | node |
| `files_transport.md` | node transport | — | node |

## E2E-стенд

1. `test_network/reset_nodes.sh` + `seed_clients.sh --force` (HTTP или
   `FM_TRANSPORT=files`).
2. Android emulator reaches node1 as `http://10.0.2.2:8444`; QR invite from
   `fmadmin qr url`.
3. Multi-node protocols (merge, excommunicate, change_policy, remove_echo)
   use `fmadmin`/`fmclient` on the nodes; Android observes and votes through
   the UI.
4. For each protocol, check both the local DB (`messages`, `outbox`,
   `outbox_local`) and the UI state; the node is the source of truth for
   results.

### Минимальный E2E-набор

- registration + policy + identity + set_name;
- subscribe/unsubscribe + accept_rules + local echo + echo with rules;
- echomail, netmail (incl. missing key → outbox), announcement, reaction
  retraction;
- vote poster/vote/retract/result; election vote; refill request/response;
- appeal poster + vote result; remote subscribe request + established;
- excommunicate, change_policy, change_rules, remove_echo;
- merge phases and address reassignment; recover;
- client_synchronize between two Android clients.

## Результаты ручного прогона

Прогон на эмуляторе (`Medium_Phone`, `-gpu swiftshader_indirect`), клиент
`:0:2` на node1 через `http://10.0.2.2:8444`. Проверялось по логам узла
(`node1/data/logs/main.log`), таблицам `node1/data/fmnode.db` и состоянию UI.

### Проверено в этой сессии

| Сценарий | Что сделано | Подтверждение | Итог |
| --- | --- | --- | --- |
| policy + identity | вход с сохранённым паролем, политика принята | Settings: `:0:2`, `FM`, `10.0.2.2:8444/api` | PASS |
| `identity.md` / `set_name` | «Change display name» → `androidE2E` | узел: `display name updated user=:0:2` | PASS |
| echomail | сообщение в Newcomers | узел: `echomail from=:0:2 subject=E2Eechomail`, `local_users=2` | PASS |
| reaction | 👍 на своём сообщении | счётчик в UI `👍 1`, узел принял пакет | PASS |
| `vote.md` poster | «New vote» → вопрос + 2 варианта | узел: `vote poster from=:0:2 echo=Newcomers` | PASS |
| `vote.md` vote | голос за `tea` | UI `votes: 1`, `tea (1)`; узел `process protocol type=vote` | PASS |
| `vote.md` retract | «Retract» | UI `votes: 0`, кнопка Retract исчезла | PASS |
| `election.md` poster | «Start election» | узел: `election poster`, `broadcast election phase=collection` | PASS |
| `ProtocolsScreen` | список активных протоколов | видны election и vote с действиями | PASS |
| `change_rules.md` (диалог) | «Rules and name» | открывается с текущими правилами, полем имени, `all chunks` | PASS (не отправлено) |
| права доступа | не-админ | кнопка Admin в Settings скрыта | PASS |
| политика ваучеров | `voucher_enable=false` | блок ваучеров в Settings скрыт | PASS |
| `#20` имя сети | `net_name="FM"` в политике | шапка и Settings показывают `FM`, не URL | PASS (после фикса) |
| `create_local_echo.md` | «Create local conference» → все 4 типа, отправка | узел: `create_local_echo from=:0:2`, ответ 403 | PASS (клиент), отказ узла — ожидаемый |
| показ ошибки узла | отказ в диалоге создания эхи | UI: «Only admins may create echoes with rules» | PASS |

Примечание: `handleCreateLocalEcho` в fm-go требует `IsAdmin`, но отвечает
текстом «Only admins may create echoes **with rules**» (сообщение скопировано
из обработчика правил). Клиент передаёт его как есть — формулировка неточная,
это сторона узла, не Android-клиента.


### Проверено с правами админа и модератора

Права выданы на стенде (прямая правка БД — штатный способ для `test_network`):
`is_admin=1` для `:0:2`, подписка на `Admin council` и `Moderators council`,
`:0:2` добавлен в `moderators` правил Newcomers (нужно для `remove_echo`).

| Сценарий | Что сделано | Подтверждение | Итог |
| --- | --- | --- | --- |
| обновление ролей | роль появилась без перерегистрации | в Settings чипы `admin`, затем `moderator` | PASS (после фикса) |
| `change_policy.md` | правка `net_name` → diff → submit | узел: `Executing policy change proposed by :0:2`, `Policy updated successfully` | PASS |
| сохранение вложенных карт | `net_name` менялся в редакторе | в политике узла `special_echogroups` и `rate_limit` без изменений | PASS |
| переподписание политики | баннер «The network policy has changed» → Accept | узел: `User :0:2 signed policy …` | PASS |
| имя сети после смены | `net_name=FME2E` | шапка и Settings показывают `FME2E` | PASS |
| `remove_echo.md` | «Remove a conference» → `dup` | узел: `Initiating echo removal vote for dup`, постер `remove/keep` в Moderators council | PASS (после фикса) |
| голосование по удалению | экран Protocols | кнопки `remove` / `keep` на постере | PASS |
| `excommunicate.md` | «Excommunicate a node» → `:0:1:0` | узел: `Excommunicate poster from :0:2 targeting :0:1:0` | PASS |
| права на совет | не модератор в Moderators council | узел: `only moderators may post to Moderators council` | PASS (отказ) |
| баннер ошибки отправки | тот же отказ | UI: «Message not sent — only moderators may post…» | PASS |
| `change_rules.md` (отправка) | «Rules and name» → Send proposal | узел сохранил `change_rules` (proto/from/node1/echo/all_chunks_only), UI «Proposal queued» | PASS |

### Проверено: сообщения, модерация, анонсы

| Сценарий | Что сделано | Подтверждение | Итог |
| --- | --- | --- | --- |
| `netmail.md` (нет ключа) | netmail на `:0:1` без ключа в кэше | UI: «Message saved to the outbox…» | PASS |
| настройки приватности | новый блок «Private messages», включён «Request missing keys» | переключатель сохраняется | PASS (после фикса) |
| `service.md` (pubkey) | отправка netmail после включения опции | узел: `api request type=service`, `pubkeys: cache hit address=:0:1` | PASS |
| `netmail.md` (доставка) | повторная отправка после получения ключа | узел: `netmail with JWE to=:0:1`, `netmail queued local` | PASS |
| `announcements.md` | тумблер «Post as announcement» в эхе с `announcements_enable` | узел сохранил `type=announcement` | PASS |
| модерация | «Moderate» → Punishment на сообщении `:0:1` | узел: `:0:2 issued punishment to :0:1`, `total punishments: 1` | PASS |
| бейдж модерации | наказанное сообщение в ленте | кнопка «Appeal a moderation action» | PASS (после фикса) |
| `appeal.md` | апелляция на наказание | узел сохранил `appeal` (`protoAddress`, `punishmentMessageId`) | PASS |
| карточка апелляции | кто модератор, кто наказан | «Moderator: :0:2», «Target: :0:1» | PASS (после фикса) |

### Проверено: merge и refill (стенд)

| Сценарий | Что сделано | Подтверждение | Итог |
| --- | --- | --- | --- |
| `merge.md` фазы | `fmadmin merge` node1↔node2, голоса админов | phase0 пропущена (1 узел), phase1: `Received vote result … result: success`, phase2 голосование принято | PASS (фазы) |
| переназначение адресов | следствие слияния | node2 переехал на `:0:2:0`, его юзер — `:0:2:2:1` | PASS (на узле) |
| клиент после merge | смена адреса пользователя на узле | баннер «Network merged: 10.0.2.2:8444 (:0:2) address is now :0:3», в Networks новый адрес `:0:3`, узел: `User :0:3 signed policy` | PASS |
| `refill.md` запрос | «Refill history» → дата → Request | узел: `created refill session`, `forwarded refill request to=:0:1` | PASS |

Слияние гонялось на двух узлах стенда. Phase 1 и phase 2 дошли до голосования,
голоса считаются, переназначение адресов отработало; полностью завершить слияние
не удалось из-за загрязнённого стенда (в nodelist попал тестовый узел `:0:1:0`,
из-за чего node2 не мог доставить очередь, и фаза 2 отклонялась). Клиентская
часть — уведомление о слиянии и принятие нового адреса — проверена и работает.

### Проверено после обнуления стенда

Стенд пересоздан (`reset_nodes.sh` + `seed_clients.sh --force`), на node1 включён
`client_synchronize`, на node2 — `recover_enabled`. Приложение заново
зарегистрировано на node1 (ключ узла сменился).

| Сценарий | Что сделано | Подтверждение | Итог |
| --- | --- | --- | --- |
| `refill.md` (ответ из приложения) | :0:1 запросил refill → в Protocols «Respond» | узел: `refill type=response from=:0:2`, `processed refill messages count=4`; :0:1 получил `history1..3` | PASS (после фикса) |
| `recover.md` | Settings → Recovery mode → инвайт node2 | node2: `recover client registered address=:0:2`, `is_recover_client=1`, создан Emergency | PASS |
| recover: RO-доступ | после recover приложение показывает только Emergency | список эх = Emergency, обычные конференции недоступны | PASS |
| recover: смена эндпоинта | профиль ушёл на `:0:2:2` / 8445 | Settings: URL 8445, ключ узла node2 | PASS (после фикса) |
| `merge.md` целиком | node1⇄node2, голоса админов в фазах | `Network1: :0: → :0:1:, Network2: :0: → :0:2:`, `Merge execution completed`; обе ноды видят `:0:1:0`, `:0:2:0` | PASS |
| клиент после merge | перерегистрация адреса | баннер «Network merged: (:0:2) address is now :0:2:2», node2: users `:0:2:2` | PASS |
| `remote_subscribe.md` | канал `RemoteChannel` на node1, подписка из приложения на `:0:1:0` | node1: `Remote subscribe approved: requester=:0:2:2 echo=RemoteChannel`; канал появился в списке эх | PASS |
| `client_synchronize` (регистрация) | node2 с `client_synchronize: true` | узел: `client_container_entries` = `{op:add, client:<uuid>}` | PASS |
| `client_synchronize` (upload) | сообщение из Emergency → второе устройство | приложение записало `{op:message, …Emergency…}` в `outbound_client_<второй клиент>` | PASS |
| `client_synchronize` (download) | `{op:blog}` в контейнер приложения | блог «Synced Blog» появился в UI, запись удалена после ack | PASS |

Второй Android-клиент не поднимался: вместо второго эмулятора контейнеры
наполнялись по тому же протоколу (`client_synchronize`), что использует второе
устройство с теми же ключами; криптоформат 1:1 с `CryptoService` (JWE:
`ephemeral_public`/`nonce`/`ciphertext`, X25519 + ChaCha20-Poly1305).

### Найдено на стороне узла (не клиента)

`remote_subscribe` регистрирует подписку, но новые сообщения канала до
удалённого подписчика не доходят: хост-узел логирует
`echomail queued … remote_nodes=0 local_users=0`, очередь в `outbound` не растёт.
Клиент показывает канал и получает по нему только то, что придёт через
NEW_ECHO/синхронизацию.

### Не проверено в этой сессии
| `conflict.md` | нужны разошедшиеся данные по одному голосованию на двух узлах (после merge данные общие) | разорвать связь, проголосовать по-разному на каждом узле, затем переподключить и дождаться обмена |

### Замечания по стенду

- Экран эмулятора засыпает (`screen_off_timeout` = 60 с) и тапы уходят в
  никуда: перед прогоном `adb shell svc power stayon true` и
  `settings put system screen_off_timeout 1800000`.
- `adb shell input text` с пробелами надо передавать одной строкой в кавычках:
  `adb shell "input text 'two words'"`, иначе `input` получает лишние аргументы.
- БД приложения зашифрована (SQLCipher), читать её с хоста нельзя — проверять
  только через UI и логи/БД узла.
- `uiautomator dump` иногда возвращает `null root node`; помогает повторный
  вызов через секунду.

## Найденные дефекты

### Имя сети `"FM"` считалось «имени нет» (#20)

`networkNameFromPolicyJson` возвращал строку `"FM"` как признак
«в политике нет `net_name`», а `NetworkGroupUi.displayName` и карточка сети
сравнивали с этим же значением. Стенд `test_network` использует
`net_name = "FM"` как обычное имя, поэтому такая сеть теряла имя и показывала
адрес узла — ровно то, что #20 запрещает.

Исправление: «имени нет» теперь пустая строка, реальное имя `"FM"` остаётся
именем; все вызывающие места используют `ifBlank { заголовок профиля }`.
Защищено тестом `NetworkNameTest.a network named FM is a real name`.

### Роли админа и модератора не обновлялись

`refreshIdentity` читал `identity.is_admin` / `is_moderator`, но применял только
имя и адрес. Роль кэшировалась из регистрации навсегда: назначенный модератор
не получал модераторский UI, а понижённый админ сохранял админский.

Исправление: `refreshIdentity` обновляет роли через
`NetworkManager.updateProfileRoles` + `NetworkSession.applyRoleChange`, как это
уже делалось для имени и адреса. Тесты: `NetworkSessionTest` (следование ролям).

### `remove_echo` не создавал голосование

Клиент отправлял `variants: ["remove", "keep"]` в предложении. Узел в
`handleRemoveEcho` проверяет `len(Variants) == 0`, чтобы понять, что это новое
предложение, и только тогда создаёт постер голосования; с `variants` он уходил
в ветку обработки чанка и ничего не делал. На стенде это выглядело как
«Proposal submitted» без результата.

Исправление: payload вынесен в `removeEchoPayload` и больше не содержит
`variants` (узел сам добавляет `remove`/`keep`). Тест:
`RemoveEchoPayloadTest`. После фикса узел логирует
`Initiating echo removal vote for dup` и создаёт постер.

### Netmail к незнакомому получателю оставался в outbox

Запрос ключа включается настройкой `auto_request_pubkeys`, которая по умолчанию
выключена (как и в fm-app). Ключи приходят только в `pubkey_response`, поэтому
без этой настройки netmail новому получателю висел в outbox **навсегда**, а
включить настройку было негде: сеттеры в `NetworkSession` были, UI — нет.

Исправление: в Settings добавлен блок «Private messages» с переключателями
«Request missing keys» (`autoRequestPubkeys`) и «Answer key requests»
(`autoSendPubkey`); флаги читаются при открытии сессии.
На стенде: включил → запрос ушёл → ключ пришёл → netmail доставлен.

### Бейдж модерации и апелляция не появлялись

`collectFeedRows` ключевал модерацию по id **самого сообщения о модерации**, а
искал по id строки ленты. В итоге бейдж вешался на само уведомление (которое
отрисовывается пустым), а наказанное сообщение оставалось без бейджа и без
кнопки Appeal — апелляция была недоступна вообще.

Исправление: карта ключуется по `originalMessageId`. Тест:
`ModerationMappingTest.the mapping key is the punished message id`.

### В карточке апелляции модератором называли наказанного

Диалог печатал `message.senderAddress` — отправителя **наказанного** сообщения.
В payload модерации есть только `to` (жертва), автор — это отправитель
уведомления.

Исправление: `ModerationUi.moderatorAddress` заполняется из отправителя
уведомления, диалог показывает его. На стенде: «Moderator: :0:2»,
«Target: :0:1».

### `change_rules` без изменений отклоняется

Предложение с текущими правилами узел отклоняет: «no differences found between
current and proposed rules». UI при этом сначала пишет «Proposal queued»
(сообщение уходит в outbox), а отказ появляется позже баннером — ожидаемо, но
можно предупреждать сразу, сравнив rules до отправки.

### Кнопка «Respond» у refill ничего не отправляла

`respondRefill` во ViewModel ставил 👍-реакцию на запрос. Узел обрабатывает
реакции только для одобрения новичков в Newcomers (`processReactionMessage`
выходит, если эхо не Newcomers), а refill-ответ — это отдельное сообщение
`proto:"refill"`, `type:"response"` с массивом `refill.messages` и их JWS.

Исправление: `NetworkSession.respondRefill(echo, requester, before)` собирает
подписанные сообщения из локальной БД (`MessageDao.getSignedBefore`) и
отправляет настоящий ответ; статус «Sent N message(s)» виден в Protocols.
Тесты: `NetworkSessionTest` (payload и пустой случай), `RefillRequestTest`.

### После recover приложение продолжало работать со старым узлом

`submitRecovery` обновлял профиль (URL, ключ узла), но `sessionFor` возвращает
закэшированную сессию по `profile.id`, а та держит старый API-клиент. В итоге
recover формально проходил, Settings показывал новый адрес, а весь трафик шёл
на мёртвый узел.

Исправление: `forgetSession(profileId)` сбрасывает сессию перед повторным
открытием; после recover приложение сразу работает с recovery-узлом.

### Ограничения стороны узла (не клиента)

- `processRemoveEchoUpdate` в fm-go — заглушка («For now, just log»), поэтому
  удаление эхи после голосования в этой сборке не выполняется.
- `handleCreateLocalEcho` требует админа, но отвечает текстом про правила
  («Only admins may create echoes with rules»).
- `remove_echo` принимается только от модератора: клиент показывает кнопку в
  админском экране, так что админ без модераторских прав получит отказ (и
  увидит его в баннере ошибок).

## Русская локализация (#32)

Проверка вёрстки на эмуляторе с системной локалью `ru-RU` (Android 8.0,
411dp). Локаль меняется без root:

```bash
adb shell settings put system system_locales ru-RU
adb reboot
adb shell am get-config | head -1   # ...-ru-rRU-...
```

Проверенные экраны: список эх, эха и карточка сообщения, композер эхопочты,
личные сообщения и композер, поиск, протоколы, группы эх, браузер эх,
создание эхи, удалённая подписка, правила/название, настройки, политика,
восстановление, QR-приглашение, админка, редактор политики, удаление эхи,
выборы/голосование, ваучеры.

| Экран | Проблема в `ru` | Исправление |
| --- | --- | --- |
| Композер личных сообщений | «Новое личное сообщение» переносилось на 3 строки и растягивало топбар | заголовок в одну строку с ellipsis, «Отправить» — иконка, строка сокращена до «Личное сообщение» |
| Браузер эх | две текстовые кнопки («Удалённо», «Создать локальную конференцию») сжимали заголовок «Эхо-конференции» до одного символа в строке | действия заменены на иконки, заголовок ограничен одной строкой |
| Админка | «Удаление конференции» и «Предложить изменение политики» в одном `Row`: вторая кнопка растягивалась на 3 строки | кнопки друг под другом, на всю ширину |
| Настройки → тип ваучера | «Одноразовое приглашение» и «Многоразовый (на время)» не помещались рядом | кнопки друг под другом, на всю ширину |
| Динамические заголовки | длинные имена эх/блогов обрезались без многоточия | `maxLines = 1` + `TextOverflow.Ellipsis` |

Правило для новых экранов: длинные русские подписи не ставить в один `Row` с
другими кнопками; для парных действий использовать `Column`/`FlowRow`, для
заголовков топбара — `maxLines = 1` и `TextOverflow.Ellipsis`.
