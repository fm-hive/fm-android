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

### Не проверено в этой сессии
| `merge.md` фазы и переназначение адресов | нужен QR/камера либо `fmadmin merge`; стенд из 5 несвязанных узлов; на узле сейчас нет второго реального узла для merge | `fmadmin merge` по сценарию `test_network`, Android — только отображение |
| `recover.md` | нужен второй узел и подписанный запрос | `fmclient`/`curl` на node2, затем экран Recovery в приложении |
| `refill.md` ответ | диалог запроса есть, но ответить должен другой пользователь | второй клиент в той же эхе |
| `appeal.md` | нужен пакет наказаний (punishment) | модерация в ModeratedEcho по сценарию стенда |
| `remote_subscribe.md` | нужны объединённые узлы | сначала merge, затем «Remote» в браузере эх |
| `conflict.md` | нужны разошедшиеся данные на двух узлах | два узла с разными голосами |
| `client_synchronize.md` | нужны два Android-клиента с одними ключами | второй эмулятор/профиль |
| netmail, announcement | не дошли руки | отдельный прогон |

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

### Ограничения стороны узла (не клиента)

- `processRemoveEchoUpdate` в fm-go — заглушка («For now, just log»), поэтому
  удаление эхи после голосования в этой сборке не выполняется.
- `handleCreateLocalEcho` требует админа, но отвечает текстом про правила
  («Only admins may create echoes with rules»).
- `remove_echo` принимается только от модератора: клиент показывает кнопку в
  админском экране, так что админ без модераторских прав получит отказ (и
  увидит его в баннере ошибок).
