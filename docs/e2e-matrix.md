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
| `excommunicate.md` | admin proposal in Admin council | `AdminScreen` | manual |
| `change_policy.md` | policy editor, chunk diff, diff preview | `PolicyParamsTest` | auto/manual |
| `change_rules.md` | rules editor, rename proposal | `NetworkSessionTest`, `RulesEditor` | auto/manual |
| `remove_echo.md` | moderators proposal dialog | `AdminScreen` | manual |
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
