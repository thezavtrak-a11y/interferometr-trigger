# ArduHUD line protocol (Wi‑Fi TCP + USB CDC OTG)

Newline-terminated ASCII (`\n`). Same commands on SoftAP TCP `:3333` and USB serial @ 115200 (ESP32-C3 CDC).

## Phone → ESP
| Command | Notes |
|---|---|
| `PING` | → `PONG` |
| `HELLO …` | → `ACK HELLO …` then `READY MOUSE` (+ optional `TOUCH_MODE` / `PEN_MODE` on round) |
| `CLICK` | → `ACK CLICK <n> <PAIR\|CONN\|NONE>` then HID if paired |
| `MOVE <dx> <dy>` | Relative mouse, ±127. **No per-MOVE ACK** |
| `SCROLL <d>` | → `ACK SCROLL <d> <tag>` |
| `RIGHT_CLICK` / `BUTTON4` / `BUTTON5` | → `ACK … <tag>` |
| `CORNER` / `FIND_CORNER` | On-device slam; → `ACK CORNER START` … `ACK CORNER DONE` |
| `BLESTAT` / `STATUS` | → `BLESTAT conn=… pair=… bond=… tag=…` |
| `CLEAR_BONDS` | Clear BLE bonds |
| `TOUCH_ON` / `TOUCH_OFF` | Round board pad only |
| `TOUCH_MODE?` | → `TOUCH_MODE 0\|1` (+ legacy `PEN_MODE`) |

## ESP → Phone
- `HB <ms> CLICK=<n> STA=<u> PAIR=<0\|1> CONN=<0\|1> …`
- `READY WIFI MOUSE` on TCP accept; USB uses same command path after `HELLO`

Boards: `esp32_c3_round_pen_link` (GC9A01), `esp32_c3_hud_link` (0.42″ OLED).
