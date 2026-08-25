#include <WiFi.h>
#include <WiFiClient.h>
#include <WiFiServer.h>
#include <Wire.h>
#include <U8g2lib.h>
#include <HijelHID_BLEMouse.h>
#include "esp_wifi.h"

/*
 * ArduHUD — ESP32-C3 + 0.42" OLED (SSD1306 72x40 @ GPIO5/6)
 * SoftAP TCP :3333 + USB CDC serial (OTG) — same line protocol as round board.
 * BLE HID relative mouse → Windows
 * See ../PROTOCOL.md
 */

static const char *AP_SSID = "ArduHUD-ESP";
static const char *AP_PASS = "arduhud123";
static const uint16_t TCP_PORT = 3333;

U8G2_SSD1306_72X40_ER_F_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE, /* clock=*/6, /* data=*/5);
HijelBLEMouse bleMouse("ArduHUD Mouse", "ArduHUD", 100, 5, false);

WiFiServer server(TCP_PORT);
WiFiClient client;

String inputLine;
String serialLine;
String lastEvent = "boot";
unsigned long lastHeartbeatMs = 0;
unsigned long lastUiMs = 0;
unsigned long clickCount = 0;
bool uiDirty = true;
bool lastBlePair = false;
bool lastBleConn = false;
uint8_t lastSta = 255;

int cornerBurstsLeft = 0;
bool cornerDraining = false;
unsigned long lastCornerStepMs = 0;
static const int CORNER_BURSTS = 100;
static const unsigned long CORNER_STEP_MS = 10;

void reply(const String &msg) {
  if (client && client.connected()) {
    client.print(msg);
    client.print('\n');
  }
  Serial.println(msg);
}

void setEvent(const String &ev) {
  lastEvent = ev;
  if (lastEvent.length() > 12) lastEvent = lastEvent.substring(0, 12);
  uiDirty = true;
}

bool bleReady() {
  return bleMouse.isPaired();
}

const char *bleTag() {
  if (bleMouse.isPaired()) return "PAIR";
  if (bleMouse.isConnected()) return "CONN";
  return "NONE";
}

void drawUi() {
  char line1[16];
  char line2[16];
  snprintf(line1, sizeof(line1), "B:%s W:%u",
           bleMouse.isPaired() ? "OK" : (bleMouse.isConnected() ? "CN" : "--"),
           (unsigned)WiFi.softAPgetStationNum());
  snprintf(line2, sizeof(line2), "%s", lastEvent.c_str());

  u8g2.clearBuffer();
  u8g2.setFont(u8g2_font_5x7_tf);
  u8g2.drawStr(0, 8, "ArduHUD");
  u8g2.drawStr(0, 18, line1);
  u8g2.drawStr(0, 30, line2);
  u8g2.sendBuffer();
  uiDirty = false;
}

bool parseIntPair(const String &line, const char *prefix, int &a, int &b) {
  if (!line.startsWith(prefix)) return false;
  return sscanf(line.c_str() + strlen(prefix), "%d %d", &a, &b) == 2;
}

bool parseIntOne(const String &line, const char *prefix, int &a) {
  if (!line.startsWith(prefix)) return false;
  return sscanf(line.c_str() + strlen(prefix), "%d", &a) == 1;
}

void handleLine(String line) {
  line.trim();
  if (line.length() == 0) return;

  if (line == "PING") {
    setEvent("PING");
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
    setEvent("CLR BOND");
    reply("ACK CLEAR_BONDS");
    return;
  }

  if (line == "CORNER" || line == "FIND_CORNER") {
    cornerBurstsLeft = CORNER_BURSTS;
    cornerDraining = false;
    lastCornerStepMs = 0;
    setEvent("CORNER");
    reply("ACK CORNER START");
    return;
  }

  if (line == "CLICK") {
    clickCount++;
    setEvent("CLICK");
    reply("ACK CLICK " + String(clickCount) + " " + bleTag());
    if (bleReady()) {
      bleMouse.click(MouseButton::Left);
    }
    return;
  }

  if (line == "RIGHT_CLICK") {
    setEvent("RCLICK");
    reply(String("ACK RIGHT_CLICK ") + bleTag());
    if (bleReady()) bleMouse.click(MouseButton::Right);
    return;
  }

  if (line == "BUTTON4") {
    setEvent("BTN4");
    reply(String("ACK BUTTON4 ") + bleTag());
    if (bleReady()) bleMouse.click(MouseButton::Back);
    return;
  }

  if (line == "BUTTON5") {
    setEvent("BTN5");
    reply(String("ACK BUTTON5 ") + bleTag());
    if (bleReady()) bleMouse.click(MouseButton::Forward);
    return;
  }

  int a = 0, b = 0;
  if (parseIntPair(line, "MOVE ", a, b)) {
    a = constrain(a, -127, 127);
    b = constrain(b, -127, 127);
    // No per-MOVE ACK — matches round board / phone coalescing.
    if (bleReady()) {
      bleMouse.move((int16_t)a, (int16_t)b);
    }
    return;
  }

  if (parseIntOne(line, "SCROLL ", a)) {
    a = constrain(a, -127, 127);
    setEvent("SCR");
    reply("ACK SCROLL " + String(a) + " " + bleTag());
    if (bleReady()) bleMouse.scroll((int16_t)a);
    return;
  }

  if (line.startsWith("ABS ") || line.startsWith("COORD ") || line.startsWith("XY ") ||
      line.startsWith("CLICK_AT ") || line == "GET_POS" || line == "POS?") {
    setEvent("NOABS");
    reply("ACK ABS IGNORED_MOUSE");
    return;
  }

  // Round-board touch aliases — no-op on OLED HUD.
  if (line == "TOUCH_ON" || line == "PEN_ON" || line == "TOUCH_OFF" || line == "PEN_OFF" ||
      line == "TOUCH_MODE?" || line == "PEN_MODE?" || line == "GET_PEN_MODE") {
    reply("TOUCH_MODE 0");
    reply("PEN_MODE 0");
    return;
  }

  if (line.startsWith("HELLO")) {
    setEvent("HELLO");
    reply("ACK " + line);
    reply("READY MOUSE");
    return;
  }

  setEvent("ECHO");
  reply("ECHO " + line);
}

void feedLineBuffer(String &buf, char ch) {
  if (ch == '\r') return;
  if (ch == '\n') {
    if (buf.length() > 0) handleLine(buf);
    buf = "";
  } else if (buf.length() < 120) {
    buf += ch;
  }
}

void setup() {
  Serial.begin(115200);
  delay(200);

  Wire.begin(5, 6);
  Wire.setClock(400000);
  u8g2.begin();
  u8g2.setContrast(255);
  setEvent("OLED OK");
  drawUi();

  bleMouse.setLogLevel(HIDLogLevel::Normal);
  bleMouse.setSecurityMode(HIDSecurity::JustWorks);
  bleMouse.setUpdateRate(HIDRate::Hz100);
  bleMouse.begin();
  setEvent("BLE adv");
  drawUi();

  WiFi.mode(WIFI_AP);
  WiFi.setSleep(false);
  esp_wifi_set_ps(WIFI_PS_NONE);

  const bool ok = WiFi.softAP(AP_SSID, AP_PASS, /*channel=*/1, /*hidden=*/0, /*max_connection=*/4);
  IPAddress ip = WiFi.softAPIP();

  Serial.println("BOOT ESP32_C3_HUD_LINK");
  Serial.printf("SoftAP %s pass=%s ok=%d ip=%s\n", AP_SSID, AP_PASS, ok ? 1 : 0, ip.toString().c_str());
  Serial.printf("BLE name: ArduHUD Mouse  TCP:%u  USB CDC OK\n", TCP_PORT);
  Serial.println("Protocol: ../PROTOCOL.md (Wi-Fi + USB OTG)");

  server.begin();
  server.setNoDelay(true);

  setEvent(ok ? "AP ready" : "AP fail");
  drawUi();
  Serial.println("READY");
}

void loop() {
  if (!client || !client.connected()) {
    WiFiClient incoming = server.available();
    if (incoming) {
      if (client) client.stop();
      client = incoming;
      client.setNoDelay(true);
      inputLine = "";
      setEvent("TCP OK");
      reply("READY WIFI MOUSE");
    }
  }

  if (client && client.connected()) {
    while (client.available() > 0) {
      feedLineBuffer(inputLine, (char)client.read());
    }
  }

  while (Serial.available() > 0) {
    feedLineBuffer(serialLine, (char)Serial.read());
  }

  if (cornerBurstsLeft > 0) {
    const unsigned long nowCorner = millis();
    if (lastCornerStepMs == 0 || nowCorner - lastCornerStepMs >= CORNER_STEP_MS) {
      lastCornerStepMs = nowCorner;
      if (bleReady()) bleMouse.move(127, -127);
      cornerBurstsLeft--;
      if (cornerBurstsLeft == 0) cornerDraining = true;
    }
  } else if (cornerDraining) {
    if (!bleMouse.hasPendingMotion()) {
      cornerDraining = false;
      setEvent("CORNER OK");
      reply("ACK CORNER DONE");
    }
  }

  const bool pairNow = bleMouse.isPaired();
  const bool connNow = bleMouse.isConnected();
  const uint8_t staNow = (uint8_t)WiFi.softAPgetStationNum();
  if (pairNow != lastBlePair || connNow != lastBleConn || staNow != lastSta) {
    lastBlePair = pairNow;
    lastBleConn = connNow;
    lastSta = staNow;
    uiDirty = true;
  }

  const unsigned long now = millis();
  if (uiDirty && (now - lastUiMs >= 120)) {
    lastUiMs = now;
    drawUi();
  }

  if (now - lastHeartbeatMs >= 5000) {
    lastHeartbeatMs = now;
    Serial.printf("HB %lu CLICK=%lu STA=%u PAIR=%d CONN=%d\n",
                  now, clickCount, staNow, pairNow ? 1 : 0, connNow ? 1 : 0);
    if (client && client.connected()) {
      client.printf("HB %lu CLICK=%lu STA=%u PAIR=%d CONN=%d\n",
                    now, clickCount, staNow, pairNow ? 1 : 0, connNow ? 1 : 0);
    }
  }

  yield();
}
