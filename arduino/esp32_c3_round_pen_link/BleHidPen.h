#pragma once

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <NimBLEHIDDevice.h>

// Absolute BLE HID digitizer / pen (0..65535). Appearance = Digital Pen.
class BleHidPen {
public:
  explicit BleHidPen(
      const char *deviceName = "ArduHUD Pen",
      const char *manufacturer = "ArduHUD");

  void begin();
  bool isConnected() const { return _connected; }

  // Absolute move; tipDown keeps tip pressed (drag).
  void moveTo(uint16_t x, uint16_t y, bool tipDown = false);
  // Tip click at current / given position.
  void clickAt(uint16_t x, uint16_t y);
  void click();  // at last absolute position
  // Stay at last coords, clear In Range (finger up) — do not warp host cursor.
  void lift();

  uint16_t lastX() const { return _x; }
  uint16_t lastY() const { return _y; }

  void setConnected(bool v) { _connected = v; }

private:
  void sendReport(uint8_t buttons, uint16_t x, uint16_t y);
  void startAdvertising();

  const char *_deviceName;
  const char *_manufacturer;
  NimBLEServer *_server = nullptr;
  NimBLEHIDDevice *_hid = nullptr;
  NimBLECharacteristic *_input = nullptr;
  bool _connected = false;
  uint16_t _x = 32767;
  uint16_t _y = 32767;

  class ServerCallbacks : public NimBLEServerCallbacks {
  public:
    explicit ServerCallbacks(BleHidPen *owner) : _owner(owner) {}
    void onConnect(NimBLEServer *s, NimBLEConnInfo &info) override;
    void onDisconnect(NimBLEServer *s, NimBLEConnInfo &info, int reason) override;
  private:
    BleHidPen *_owner;
  };

  ServerCallbacks *_cbs = nullptr;
};
