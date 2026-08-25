/*
 * ArduHUD — ESP32-2424S012C relative mouse bridge
 *
 * Hardware: ESP32-C3 + GC9A01 240x240 + CST816D
 *   LCD SPI: SCLK=6 MOSI=7 DC=2 CS=10 BL=3
 *   Touch I2C: SDA=4 SCL=5 INT=0 RST=1  addr 0x15
 *
 * SoftAP → TCP 3333 → BLE HID mouse (relative)
 * Touch: 1-finger drag = MOVE (when PAD ON)
 *        single tap = CLICK (filtered)
 *        triple tap = PAD ON/OFF (purple / black)
 * Any CLICK (touch or phone) → center flash
 *
 * Libs: LovyanGFX, HijelHID_BLEMouse (NimBLE)
 */

#include <WiFi.h>
#include <WiFiClient.h>
#include <WiFiServer.h>
#include <Wire.h>
#include <math.h>
#include <HijelHID_BLEMouse.h>
#include "esp_wifi.h"
#include "LGFX_ESP32_2424S012C.h"
#include "CstTouch.h"

static const char *AP_SSID = "ArduHUD-ESP";
static const char *AP_PASS = "arduhud123";
static const uint16_t TCP_PORT = 3333;

static const int PIN_TOUCH_SDA = 4;
static const int PIN_TOUCH_SCL = 5;
static const int PIN_TOUCH_INT = 0;
static const int PIN_TOUCH_RST = 1;
static const uint8_t CST_ADDR = 0x15;

static const int SCR = 240;
static const int CX = 120;
static const int CY = 120;
static const int RIM_R = 96;
static const int GUIDE_R = RIM_R - 2;
static const float TOUCH_MOVE_SCALE = 1.6875f;  // was 1.35 × 1.25

// Tap filter / multi-tap
static const uint32_t MIN_PRESS_MS = 30;       // ignore contact bounce
static const uint32_t MAX_PRESS_MS = 280;      // longer = not a tap
static const int TAP_SLOP2 = 14 * 14;          // max travel² for tap
static const int DRAG_SLOP2 = 20 * 20;         // travel² to start drag
static const uint32_t MIN_TAP_GAP_MS = 100;    // debounce between taps
static const uint32_t TAP_SEQ_MS = 420;        // window to gather multi-tap
static const uint32_t TOGGLE_COOLDOWN_MS = 550;
static const uint8_t TAPS_FOR_TOGGLE = 3;

static const uint16_t COL_BG_OFF = TFT_BLACK;
static const uint16_t COL_BG_ON = 0x4810;   // purple-ish
static const uint16_t COL_WIFI_OFF = 0xD928;
static const uint16_t COL_WIFI_OK = 0x254F;
static const uint16_t COL_BLE_OFF = 0xFCB2;
static const uint16_t COL_BLE_OK = 0x259A;
static const uint16_t COL_ERR_A = 0xF800;
static const uint16_t COL_ERR_B = 0xFB16;
static const uint16_t COL_LOG = TFT_WHITE;
static const uint16_t COL_FLASH = 0xFFE0;  // yellow

LGFX tft;
// Distinct name — phone app also used to register as "ArduHUD Mouse".
HijelBLEMouse bleMouse("ArduHUD ESP", "ArduHUD", 100, 5, false);
LGFX_Sprite glyphSprite(&tft);
// Offscreen frame — fillScreen on TFT flashes black; pushSprite is one blit.
LGFX_Sprite frameBuf(&tft);
lgfx::LovyanGFX *gfx = &tft;
bool frameBufOk = false;

WiFiServer server(TCP_PORT);
WiFiClient client;
String inputLine;
String serialLine;

String lastCmd = "boot";
unsigned long clickCount = 0;
unsigned long lastUiMs = 0;
unsigned long lastHbMs = 0;
unsigned long flashUntilMs = 0;
bool uiDirty = true;
bool lastBlePair = false;
bool lastBleConn = false;
uint8_t lastSta = 255;
bool wifiError = false;
bool wifiApOk = false;

bool touchControlEnabled = false;  // start OFF — purple when ON
bool touching = false;
bool dragging = false;
int pressX = CX;
int pressY = CY;
int lastTouchX = CX;
int lastTouchY = CY;
int pressTravel2 = 0;
unsigned long pressStartMs = 0;
float arrowAngle = -PI / 2;
bool showArrow = false;

uint8_t tapSeqCount = 0;
unsigned long lastTapMs = 0;
unsigned long tapSeqDeadlineMs = 0;
unsigned long ignoreTapsUntilMs = 0;

// Phone «Найти»: slam on-device (avoids TCP MOVE flood + ACK storm).
int cornerBurstsLeft = 0;
bool cornerDraining = false;
unsigned long lastCornerStepMs = 0;
static const int CORNER_BURSTS = 100;
static const unsigned long CORNER_STEP_MS = 10;

// ---------------------------------------------------------------------------

void setCmd(const String &s) {
  lastCmd = s;
  if (lastCmd.length() > 18) lastCmd = lastCmd.substring(0, 18);
  uiDirty = true;
}

void reply(const String &msg) {
  if (client && client.connected()) {
    client.print(msg);
    client.print('\n');
  }
  Serial.println(msg);
}

bool parseIntPair(const String &line, const char *prefix, int &a, int &b) {
  if (!line.startsWith(prefix)) return false;
  return sscanf(line.c_str() + strlen(prefix), "%d %d", &a, &b) == 2;
}

bool parseIntOne(const String &line, const char *prefix, int &a) {
  if (!line.startsWith(prefix)) return false;
  return sscanf(line.c_str() + strlen(prefix), "%d", &a) == 1;
}

void notifyTouchMode() {
  reply(String("TOUCH_MODE ") + (touchControlEnabled ? "1" : "0"));
  // Keep legacy alias for phone code that listened to PEN_MODE
  reply(String("PEN_MODE ") + (touchControlEnabled ? "1" : "0"));
}

void setTouchControl(bool on) {
  if (touchControlEnabled == on) return;
  touchControlEnabled = on;
  setCmd(on ? "TOUCH ON" : "TOUCH OFF");
  notifyTouchMode();
  uiDirty = true;
}

void triggerClickFlash() {
  flashUntilMs = millis() + 180;
  uiDirty = true;
}

// Hijel only transmits HID after isPaired() (encrypted). isConnected() alone is not enough.
bool bleReady() {
  return bleMouse.isPaired();
}

const char *bleTag() {
  if (bleMouse.isPaired()) return "PAIR";
  if (bleMouse.isConnected()) return "CONN";
  return "NONE";
}

void doMouseClick() {
  clickCount++;
  triggerClickFlash();
  if (bleReady()) {
    bleMouse.click(MouseButton::Left);
  }
}

// ---------------------------------------------------------------------------
// CST816D — 1–2 fingers
// ---------------------------------------------------------------------------

void touchReset() {
  pinMode(PIN_TOUCH_RST, OUTPUT);
  pinMode(PIN_TOUCH_INT, INPUT_PULLUP);
  digitalWrite(PIN_TOUCH_RST, LOW);
  delay(10);
  digitalWrite(PIN_TOUCH_RST, HIGH);
  delay(50);
}

CstTouch readCstTouch() {
  CstTouch t;
  t.down = false;
  t.fingers = 0;
  t.gesture = 0;
  t.x1 = t.y1 = t.x2 = t.y2 = 0;
  Wire.beginTransmission(CST_ADDR);
  Wire.write(0x01);
  if (Wire.endTransmission(false) != 0) return t;
  const int n = Wire.requestFrom((int)CST_ADDR, 10);
  if (n < 6) return t;
  t.gesture = Wire.read();
  t.fingers = Wire.read() & 0x0F;
  const uint8_t xh = Wire.read();
  const uint8_t xl = Wire.read();
  const uint8_t yh = Wire.read();
  const uint8_t yl = Wire.read();
  t.x1 = ((xh & 0x0F) << 8) | xl;
  t.y1 = ((yh & 0x0F) << 8) | yl;
  if (n >= 10 && t.fingers >= 2) {
    const uint8_t x2h = Wire.read();
    const uint8_t x2l = Wire.read();
    const uint8_t y2h = Wire.read();
    const uint8_t y2l = Wire.read();
    t.x2 = ((x2h & 0x0F) << 8) | x2l;
    t.y2 = ((y2h & 0x0F) << 8) | y2l;
  } else {
    t.x2 = t.x1;
    t.y2 = t.y1;
  }
  if (t.fingers == 0) return t;
  if (t.x1 == 0 && t.y1 == 0) return t;
  t.down = true;
  return t;
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

uint16_t blinkErrorColor() {
  return ((millis() / 280) & 1) ? COL_ERR_A : COL_ERR_B;
}

uint16_t wifiStatusColor(uint8_t sta) {
  if (wifiError || !wifiApOk) return blinkErrorColor();
  return (sta > 0) ? COL_WIFI_OK : COL_WIFI_OFF;
}

uint16_t bleStatusColor(bool connected) {
  return connected ? COL_BLE_OK : COL_BLE_OFF;
}

void ensureGlyphSprite() {
  if (glyphSprite.width() > 0) return;
  glyphSprite.setColorDepth(8);
  glyphSprite.createSprite(18, 22);
  glyphSprite.setFont(&fonts::Font2);
  glyphSprite.setTextSize(1);
}

bool ensureFrameBuf() {
  if (frameBufOk) return true;
  // 8-bit ≈ 57KB — fits ESP32-C3 with WiFi+BLE; 16-bit often OOM.
  frameBuf.setColorDepth(8);
  frameBufOk = frameBuf.createSprite(SCR, SCR);
  if (!frameBufOk) {
    Serial.println("UI: frameBuf OOM — direct TFT (may flicker)");
  }
  return frameBufOk;
}

void drawRimText(const char *text, float startDeg, uint16_t color) {
  if (!text || !*text) return;
  ensureGlyphSprite();
  const int n = (int)strlen(text);
  const float start = startDeg * PI / 180.0f;
  const float step = 11.0f * PI / 180.0f;
  const int gw = glyphSprite.width();
  const int gh = glyphSprite.height();
  const uint16_t key = 0x0000;
  for (int i = 0; i < n; i++) {
    if (text[i] == ' ') continue;
    const float a = start + i * step;
    const int x = CX + (int)(RIM_R * cosf(a));
    const int y = CY + (int)(RIM_R * sinf(a));
    const float angleDeg = a * 180.0f / PI + 90.0f;
    glyphSprite.fillScreen(key);
    glyphSprite.setTextColor(color, key);
    glyphSprite.setTextDatum(BC_DATUM);
    char buf[2] = {text[i], 0};
    glyphSprite.drawString(buf, gw / 2, gh - 1);
    glyphSprite.drawString(buf, gw / 2 + 1, gh - 1);
    glyphSprite.setPivot(gw / 2.0f, (float)(gh - 1));
    glyphSprite.pushRotateZoom(gfx, (float)x, (float)y, angleDeg, 1.0f, 1.0f, key);
  }
}

void drawArrow(float angle) {
  const float len = 58.0f;
  const float hx = CX + cosf(angle) * len;
  const float hy = CY + sinf(angle) * len;
  const float back = angle + PI;
  const float wing = 0.40f;
  const float wlen = 10.0f;
  gfx->drawLine(CX, CY, (int)hx, (int)hy, TFT_CYAN);
  const int x1 = (int)(hx + cosf(back + wing) * wlen);
  const int y1 = (int)(hy + sinf(back + wing) * wlen);
  const int x2 = (int)(hx + cosf(back - wing) * wlen);
  const int y2 = (int)(hy + sinf(back - wing) * wlen);
  gfx->fillTriangle((int)hx, (int)hy, x1, y1, x2, y2, TFT_YELLOW);
  gfx->fillCircle(CX, CY, 2, TFT_WHITE);
}

void drawUi() {
  const bool buffered = ensureFrameBuf();
  gfx = buffered ? static_cast<lgfx::LovyanGFX *>(&frameBuf)
                 : static_cast<lgfx::LovyanGFX *>(&tft);

  const uint16_t bg = touchControlEnabled ? COL_BG_ON : COL_BG_OFF;
  gfx->fillScreen(bg);
  gfx->drawCircle(CX, CY, GUIDE_R, TFT_DARKGREY);

  const uint8_t sta = (uint8_t)WiFi.softAPgetStationNum();

  char wifiLine[24];
  char bleLine[24];
  char modeLine[16];
  snprintf(wifiLine, sizeof(wifiLine), "WIFI STA:%u", (unsigned)sta);
  snprintf(bleLine, sizeof(bleLine), "BLE %s", bleTag());
  snprintf(modeLine, sizeof(modeLine), "PAD %s", touchControlEnabled ? "ON" : "OFF");

  drawRimText(wifiLine, -155.0f, wifiStatusColor(sta));
  drawRimText(bleLine, 15.0f, bleStatusColor(bleMouse.isPaired()));
  drawRimText(modeLine, -40.0f, touchControlEnabled ? COL_WIFI_OK : COL_WIFI_OFF);
  drawRimText(lastCmd.c_str(), 105.0f, COL_LOG);

  if (touchControlEnabled && showArrow) {
    drawArrow(arrowAngle);
  } else {
    gfx->fillCircle(CX, CY, 2, TFT_DARKGREY);
  }
  gfx->drawCircle(CX, CY, 36, TFT_NAVY);

  if (millis() < flashUntilMs) {
    gfx->fillCircle(CX, CY, 28, COL_FLASH);
    gfx->fillCircle(CX, CY, 14, TFT_WHITE);
  }

  if (buffered) {
    frameBuf.pushSprite(0, 0);
  }
  uiDirty = false;
}

// ---------------------------------------------------------------------------
// TCP protocol
// ---------------------------------------------------------------------------

void handleLine(String line) {
  line.trim();
  if (line.length() == 0) return;

  if (line == "PING") {
    setCmd("PING");
    reply("PONG");
    return;
  }

  if (line == "BLESTAT" || line == "STATUS") {
    reply(String("BLESTAT conn=") + (bleMouse.isConnected() ? "1" : "0") +
          " pair=" + (bleMouse.isPaired() ? "1" : "0") +
          " bond=" + (bleMouse.isBonded() ? "1" : "0") +
          " tag=" + bleTag());
    return;
  }

  if (line == "CLEAR_BONDS") {
    bleMouse.clearBonds();
    setCmd("CLR BOND");
    reply("ACK CLEAR_BONDS");
    return;
  }

  if (line == "CORNER" || line == "FIND_CORNER") {
    cornerBurstsLeft = CORNER_BURSTS;
    cornerDraining = false;
    lastCornerStepMs = 0;
    setCmd("CORNER");
    reply("ACK CORNER START");
    return;
  }

  if (line == "CLICK") {
    setCmd("CLICK");
    if (bleReady()) {
      doMouseClick();
      reply("ACK CLICK " + String(clickCount) + " PAIR");
    } else {
      triggerClickFlash();
      clickCount++;
      reply("ACK CLICK " + String(clickCount) + " " + bleTag());
    }
    return;
  }

  int a = 0, b = 0;
  if (parseIntPair(line, "MOVE ", a, b)) {
    a = constrain(a, -127, 127);
    b = constrain(b, -127, 127);
    // No per-MOVE ACK/UI — flood was stalling TCP and lagging the cursor.
    if (bleReady()) {
      bleMouse.move((int16_t)a, (int16_t)b);
    }
    return;
  }

  if (parseIntOne(line, "SCROLL ", a)) {
    a = constrain(a, -127, 127);
    setCmd("SCR");
    if (bleReady()) {
      bleMouse.scroll((int16_t)a);
      reply("ACK SCROLL " + String(a) + " PAIR");
    } else {
      reply("ACK SCROLL " + String(a) + " " + bleTag());
    }
    return;
  }

  if (line == "RIGHT_CLICK") {
    setCmd("RCLICK");
    triggerClickFlash();
    if (bleReady()) {
      bleMouse.click(MouseButton::Right);
      reply("ACK RIGHT_CLICK PAIR");
    } else {
      reply("ACK RIGHT_CLICK " + String(bleTag()));
    }
    return;
  }

  if (line == "BUTTON4") {
    setCmd("BTN4");
    if (bleReady()) {
      bleMouse.click(MouseButton::Back);
      reply("ACK BUTTON4 PAIR");
    } else {
      reply("ACK BUTTON4 " + String(bleTag()));
    }
    return;
  }

  if (line == "BUTTON5") {
    setCmd("BTN5");
    if (bleReady()) {
      bleMouse.click(MouseButton::Forward);
      reply("ACK BUTTON5 PAIR");
    } else {
      reply("ACK BUTTON5 " + String(bleTag()));
    }
    return;
  }

  // Absolute coords not supported on relative mouse — keep ACK for phone UI.
  if (line.startsWith("ABS ") || line.startsWith("COORD ") || line.startsWith("XY ") ||
      line.startsWith("CLICK_AT ") || line == "GET_POS" || line == "POS?") {
    setCmd("NOABS");
    reply("ACK ABS IGNORED_MOUSE");
    return;
  }

  if (line == "TOUCH_ON" || line == "PEN_ON") {
    setTouchControl(true);
    return;
  }
  if (line == "TOUCH_OFF" || line == "PEN_OFF") {
    setTouchControl(false);
    return;
  }
  if (line == "TOUCH_MODE?" || line == "PEN_MODE?" || line == "GET_PEN_MODE") {
    notifyTouchMode();
    return;
  }

  if (line.startsWith("HELLO")) {
    setCmd("HELLO");
    reply("ACK " + line);
    reply("READY MOUSE");
    notifyTouchMode();
    return;
  }

  setCmd("ECHO");
  reply("ECHO " + line);
}

// ---------------------------------------------------------------------------
// Touch → mouse
// ---------------------------------------------------------------------------

void flushPendingTaps() {
  if (tapSeqCount == 0 || tapSeqCount >= TAPS_FOR_TOGGLE) return;
  if (millis() < tapSeqDeadlineMs) return;
  const uint8_t n = tapSeqCount;
  tapSeqCount = 0;
  for (uint8_t i = 0; i < n; i++) {
    setCmd(i == 0 ? "TAP" : "TAP+");
    if (bleMouse.isConnected()) {
      doMouseClick();
    } else {
      triggerClickFlash();
      clickCount++;
    }
    delay(35);
  }
}

void onQualifiedTap() {
  const unsigned long now = millis();
  if (now < ignoreTapsUntilMs) return;
  if (now - lastTapMs < MIN_TAP_GAP_MS) return;  // chatter filter

  if (now > tapSeqDeadlineMs) {
    tapSeqCount = 0;
  }
  lastTapMs = now;
  tapSeqCount++;
  tapSeqDeadlineMs = now + TAP_SEQ_MS;

  if (tapSeqCount >= TAPS_FOR_TOGGLE) {
    tapSeqCount = 0;
    ignoreTapsUntilMs = now + TOGGLE_COOLDOWN_MS;
    setTouchControl(!touchControlEnabled);
    setCmd(touchControlEnabled ? "PAD ON" : "PAD OFF");
    return;
  }
  // 1–2 taps resolved later in flushPendingTaps()
}

void handleTouchLogic() {
  const CstTouch t = readCstTouch();
  const unsigned long now = millis();

  if (!t.down) {
    if (touching) {
      const unsigned long held = now - pressStartMs;
      const bool isTap = !dragging &&
          held >= MIN_PRESS_MS &&
          held <= MAX_PRESS_MS &&
          pressTravel2 <= TAP_SLOP2;
      if (isTap) {
        onQualifiedTap();
      }
      touching = false;
      dragging = false;
      pressTravel2 = 0;
      uiDirty = true;
    }
    return;
  }

  if (!touching) {
    touching = true;
    dragging = false;
    pressStartMs = now;
    pressX = t.x1;
    pressY = t.y1;
    lastTouchX = t.x1;
    lastTouchY = t.y1;
    pressTravel2 = 0;
    return;
  }

  const int tdx = t.x1 - pressX;
  const int tdy = t.y1 - pressY;
  const int travel2 = tdx * tdx + tdy * tdy;
  if (travel2 > pressTravel2) pressTravel2 = travel2;

  // Drag only while PAD ON; cancels tap for this contact.
  if (touchControlEnabled && !dragging && pressTravel2 >= DRAG_SLOP2) {
    dragging = true;
    tapSeqCount = 0;  // abort multi-tap
  }

  if (dragging && touchControlEnabled) {
    int dx = (int)lroundf((t.x1 - lastTouchX) * TOUCH_MOVE_SCALE);
    int dy = (int)lroundf((t.y1 - lastTouchY) * TOUCH_MOVE_SCALE);
    lastTouchX = t.x1;
    lastTouchY = t.y1;
    dx = constrain(dx, -127, 127);
    dy = constrain(dy, -127, 127);
    if (dx != 0 || dy != 0) {
      if ((dx * dx + dy * dy) > 4) {
        arrowAngle = atan2f((float)dy, (float)dx);
        showArrow = true;
        // Arrow only — avoid setCmd() string churn every sample.
        uiDirty = true;
      }
      if (bleReady()) {
        bleMouse.move((int16_t)dx, (int16_t)dy);
      }
    }
  } else {
    lastTouchX = t.x1;
    lastTouchY = t.y1;
  }
}

// ---------------------------------------------------------------------------

void setup() {
  Serial.begin(115200);
  delay(200);

  tft.init();
  tft.setBrightness(180);
  tft.setRotation(0);
  setCmd("LCD OK");
  drawUi();

  Wire.begin(PIN_TOUCH_SDA, PIN_TOUCH_SCL);
  Wire.setClock(400000);
  touchReset();
  setCmd("TOUCH OK");
  drawUi();

  bleMouse.setLogLevel(HIDLogLevel::Normal);  // Serial: Connected / Paired
  bleMouse.setSecurityMode(HIDSecurity::JustWorks);
  bleMouse.setUpdateRate(HIDRate::Hz100);     // 10 ms reports; move() now accumulates
  bleMouse.begin();
  Serial.printf("BLE bonds stored: %d\n", (int)bleMouse.isBonded());
  setCmd("BLE adv");
  drawUi();

  WiFi.mode(WIFI_AP);
  WiFi.setSleep(false);
  esp_wifi_set_ps(WIFI_PS_NONE);
  wifiApOk = WiFi.softAP(AP_SSID, AP_PASS, 1, 0, 4);
  wifiError = !wifiApOk;
  server.begin();
  server.setNoDelay(true);

  Serial.println("BOOT ESP32_2424S012C_MOUSE_LINK");
  Serial.printf("SoftAP %s / %s  TCP:%u\n", AP_SSID, AP_PASS, TCP_PORT);
  Serial.println("BLE name: ArduHUD ESP (relative mouse)");
  Serial.println("HINT: Windows must show BLE PAIR (encrypted). CONN-only = no cursor.");
  Serial.println("HINT: Remove old 'ArduHUD Mouse'/'ArduHUD Pen', pair 'ArduHUD ESP'.");
  setCmd(wifiApOk ? "AP ready" : "AP fail");
  drawUi();
}

void loop() {
  if (!client || !client.connected()) {
    WiFiClient incoming = server.available();
    if (incoming) {
      if (client) client.stop();
      client = incoming;
      client.setNoDelay(true);
      inputLine = "";
      setCmd("TCP OK");
      reply("READY WIFI MOUSE");
      reply("READY MOUSE");
      notifyTouchMode();
    }
  }

  if (client && client.connected()) {
    while (client.available() > 0) {
      char ch = (char)client.read();
      if (ch == '\r') continue;
      if (ch == '\n') {
        handleLine(inputLine);
        inputLine = "";
      } else if (inputLine.length() < 120) {
        inputLine += ch;
      }
    }
  }

  while (Serial.available() > 0) {
    char ch = (char)Serial.read();
    if (ch == '\r') continue;
    if (ch == '\n') {
      if (serialLine.length() > 0) handleLine(serialLine);
      serialLine = "";
    } else if (serialLine.length() < 120) {
      serialLine += ch;
    }
  }

  handleTouchLogic();
  flushPendingTaps();

  if (cornerBurstsLeft > 0) {
    const unsigned long nowCorner = millis();
    if (lastCornerStepMs == 0 || nowCorner - lastCornerStepMs >= CORNER_STEP_MS) {
      lastCornerStepMs = nowCorner;
      if (bleReady()) {
        bleMouse.move(127, -127);
      }
      cornerBurstsLeft--;
      if (cornerBurstsLeft == 0) {
        cornerDraining = true;  // wait HID queue empty before DONE
      }
    }
  } else if (cornerDraining) {
    if (!bleMouse.hasPendingMotion()) {
      cornerDraining = false;
      setCmd("CORNER OK");
      reply("ACK CORNER DONE");
    }
  }

  const bool pairNow = bleMouse.isPaired();
  const bool connNow = bleMouse.isConnected();
  const uint8_t staNow = (uint8_t)WiFi.softAPgetStationNum();
  if (pairNow != lastBlePair || connNow != lastBleConn || staNow != lastSta) {
    Serial.printf("BLE_STATE conn=%d pair=%d bond=%d tag=%s sta=%u\n",
                  connNow ? 1 : 0, pairNow ? 1 : 0,
                  bleMouse.isBonded() ? 1 : 0, bleTag(), staNow);
    lastBlePair = pairNow;
    lastBleConn = connNow;
    lastSta = staNow;
    uiDirty = true;
  }

  const unsigned long now = millis();
  if (flashUntilMs != 0 && now >= flashUntilMs) {
    flashUntilMs = 0;
    uiDirty = true;
  }
  // Sticky AP/error: refresh slowly so rim colors stay correct without spam.
  static unsigned long lastErrorUiMs = 0;
  if ((wifiError || !wifiApOk) && (now - lastErrorUiMs >= 500)) {
    lastErrorUiMs = now;
    uiDirty = true;
  }

  if (uiDirty && (now - lastUiMs >= 50)) {
    lastUiMs = now;
    drawUi();
  }

  if (now - lastHbMs >= 5000) {
    lastHbMs = now;
    Serial.printf("HB %lu CLICK=%lu STA=%u PAIR=%d CONN=%d PAD=%d\n",
                  now, clickCount, staNow, pairNow ? 1 : 0, connNow ? 1 : 0,
                  touchControlEnabled ? 1 : 0);
    if (client && client.connected()) {
      client.printf("HB %lu CLICK=%lu STA=%u PAIR=%d CONN=%d PAD=%d\n",
                    now, clickCount, staNow, pairNow ? 1 : 0, connNow ? 1 : 0,
                    touchControlEnabled ? 1 : 0);
    }
  }

  yield();
}
