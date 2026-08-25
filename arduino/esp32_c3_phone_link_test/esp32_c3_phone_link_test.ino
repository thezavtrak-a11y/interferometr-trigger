#if !defined(ARDUINO_USB_MODE)
#define ARDUINO_USB_MODE 1
#endif

#if !defined(ARDUINO_USB_CDC_ON_BOOT)
#define ARDUINO_USB_CDC_ON_BOOT 1
#endif

String inputLine;
unsigned long lastHeartbeatMs = 0;
unsigned long clickCount = 0;

void setup() {
  Serial.begin(115200);
  delay(1200);

  Serial.println("BOOT ESP32_C3_PHONE_LINK_TEST");
  Serial.println("READY");
  Serial.println("CMDS: PING, CLICK, HELLO ..., ECHO ...");
}

void loop() {
  while (Serial.available() > 0) {
    char ch = static_cast<char>(Serial.read());
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

  const unsigned long now = millis();
  if (now - lastHeartbeatMs >= 3000) {
    lastHeartbeatMs = now;
    Serial.printf("HB %lu CLICK_COUNT=%lu\n", now, clickCount);
  }
}

void handleLine(String line) {
  line.trim();
  if (line.length() == 0) {
    return;
  }

  if (line == "PING") {
    Serial.println("PONG");
    return;
  }

  if (line == "CLICK") {
    clickCount++;
    Serial.printf("ACK CLICK %lu\n", clickCount);
    return;
  }

  if (line.startsWith("HELLO")) {
    Serial.print("ACK ");
    Serial.println(line);
    return;
  }

  if (line.startsWith("MOVE ")) {
    Serial.print("ACK ");
    Serial.println(line);
    return;
  }

  if (line.startsWith("SCROLL ")) {
    Serial.print("ACK ");
    Serial.println(line);
    return;
  }

  if (line == "RIGHT_CLICK" || line == "BUTTON4" || line == "BUTTON5") {
    Serial.print("ACK ");
    Serial.println(line);
    return;
  }

  Serial.print("ECHO ");
  Serial.println(line);
}
