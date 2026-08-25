#include <WiFi.h>
#include <WiFiClient.h>
#include <WiFiServer.h>

// SoftAP + TCP line protocol (same commands as USB test sketch).
static const char *AP_SSID = "ArduHUD-ESP";
static const char *AP_PASS = "arduhud123";
static const uint16_t TCP_PORT = 3333;

WiFiServer server(TCP_PORT);
WiFiClient client;

String inputLine;
unsigned long lastHeartbeatMs = 0;
unsigned long clickCount = 0;

void reply(const String &msg) {
  if (client && client.connected()) {
    client.println(msg);
    client.flush();
  }
  Serial.println(msg);
}

void handleLine(String line) {
  line.trim();
  if (line.length() == 0) {
    return;
  }

  Serial.print("RX: ");
  Serial.println(line);

  if (line == "PING") {
    reply("PONG");
    return;
  }

  if (line == "CLICK") {
    clickCount++;
    reply("ACK CLICK " + String(clickCount));
    return;
  }

  if (line.startsWith("HELLO")) {
    reply("ACK " + line);
    return;
  }

  if (line.startsWith("MOVE ") || line.startsWith("SCROLL ")) {
    reply("ACK " + line);
    return;
  }

  if (line == "RIGHT_CLICK" || line == "BUTTON4" || line == "BUTTON5") {
    reply("ACK " + line);
    return;
  }

  reply("ECHO " + line);
}

void setup() {
  Serial.begin(115200);
  delay(500);

  WiFi.mode(WIFI_AP);
  const bool ok = WiFi.softAP(AP_SSID, AP_PASS);
  IPAddress ip = WiFi.softAPIP();

  Serial.println("BOOT ESP32_C3_WIFI_AP_LINK");
  Serial.printf("SoftAP %s  pass=%s  ok=%d\n", AP_SSID, AP_PASS, ok ? 1 : 0);
  Serial.print("AP IP: ");
  Serial.println(ip);
  Serial.printf("TCP port: %u\n", TCP_PORT);

  server.begin();
  server.setNoDelay(true);

  Serial.println("READY");
  Serial.println("CMDS: PING, CLICK, HELLO ..., MOVE/SCROLL, ECHO ...");
}

void loop() {
  if (!client || !client.connected()) {
    WiFiClient incoming = server.available();
    if (incoming) {
      client.stop();
      client = incoming;
      client.setNoDelay(true);
      inputLine = "";
      Serial.println("TCP client connected");
      reply("READY WIFI");
    }
  }

  if (client && client.connected()) {
    while (client.available() > 0) {
      char ch = static_cast<char>(client.read());
      if (ch == '\r') {
        continue;
      }
      if (ch == '\n') {
        handleLine(inputLine);
        inputLine = "";
      } else if (inputLine.length() < 120) {
        inputLine += ch;
      }
    }
  }

  const unsigned long now = millis();
  if (now - lastHeartbeatMs >= 3000) {
    lastHeartbeatMs = now;
    const String hb = "HB " + String(now) + " CLICK_COUNT=" + String(clickCount) +
                      " STA=" + String(WiFi.softAPgetStationNum());
    if (client && client.connected()) {
      client.println(hb);
    }
    Serial.println(hb);
  }
}
