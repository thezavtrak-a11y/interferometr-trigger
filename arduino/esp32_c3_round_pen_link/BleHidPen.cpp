#include "BleHidPen.h"

// Report ID 1 — Digitizer Pen, absolute X/Y 0..65535
static const uint8_t kPenReportMap[] = {
    0x05, 0x0D,        // Usage Page (Digitizers)
    0x09, 0x02,        // Usage (Pen)
    0xA1, 0x01,        // Collection (Application)
    0x85, 0x01,        //   Report ID (1)
    0x09, 0x20,        //   Usage (Stylus)
    0xA1, 0x00,        //   Collection (Physical)
    0x09, 0x42,        //     Usage (Tip Switch)
    0x09, 0x32,        //     Usage (In Range)
    0x15, 0x00,        //     Logical Minimum (0)
    0x25, 0x01,        //     Logical Maximum (1)
    0x75, 0x01,        //     Report Size (1)
    0x95, 0x02,        //     Report Count (2)
    0x81, 0x02,        //     INPUT (Data,Var,Abs)
    0x95, 0x06,        //     Report Count (6) padding
    0x81, 0x03,        //     INPUT (Const,Var,Abs)
    0x05, 0x01,        //     Usage Page (Generic Desktop)
    0x09, 0x30,        //     Usage (X)
    0x09, 0x31,        //     Usage (Y)
    0x16, 0x00, 0x00,  //     Logical Minimum (0)
    0x26, 0xFF, 0x7F,  //     Logical Maximum (32767)
    0x36, 0x00, 0x00,  //     Physical Minimum (0)
    0x46, 0xFF, 0x7F,  //     Physical Maximum (32767)
    0x66, 0x00, 0x00,  //     Unit (None)
    0x75, 0x10,        //     Report Size (16)
    0x95, 0x02,        //     Report Count (2)
    0x81, 0x02,        //     INPUT (Data,Var,Abs)
    0xC0,              //   End Collection
    0xC0,              // End Collection
};

void BleHidPen::ServerCallbacks::onConnect(NimBLEServer *, NimBLEConnInfo &) {
  _owner->_connected = true;
}

void BleHidPen::ServerCallbacks::onDisconnect(NimBLEServer *, NimBLEConnInfo &, int) {
  _owner->_connected = false;
  _owner->startAdvertising();
}

BleHidPen::BleHidPen(const char *deviceName, const char *manufacturer)
    : _deviceName(deviceName), _manufacturer(manufacturer) {}

void BleHidPen::begin() {
  NimBLEDevice::init(_deviceName);
  NimBLEDevice::setSecurityAuth(BLE_SM_PAIR_AUTHREQ_BOND | BLE_SM_PAIR_AUTHREQ_SC);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);

  _server = NimBLEDevice::createServer();
  _cbs = new ServerCallbacks(this);
  _server->setCallbacks(_cbs);
  _server->advertiseOnDisconnect(false);

  _hid = new NimBLEHIDDevice(_server);
  _hid->setManufacturer(_manufacturer);
  _hid->setPnp(0x02, 0xE502, 0xA111, 0x0110);
  _hid->setHidInfo(0x00, 0x01);
  _hid->setReportMap(const_cast<uint8_t *>(kPenReportMap), sizeof(kPenReportMap));
  _input = _hid->getInputReport(1);
  _hid->setBatteryLevel(100);
  _hid->startServices();

  startAdvertising();
}

void BleHidPen::startAdvertising() {
  NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
  adv->setAppearance(0x03C7);  // HID Digital Pen
  adv->setName(_deviceName);
  adv->addServiceUUID(_hid->getHidService()->getUUID());
  adv->addServiceUUID(_hid->getBatteryService()->getUUID());
  adv->enableScanResponse(true);
  adv->start();
}

void BleHidPen::sendReport(uint8_t buttons, uint16_t x, uint16_t y) {
  if (!_connected || !_input) {
    return;
  }
  // Map 0..65535 → 0..32767 logical range used in descriptor.
  const uint16_t lx = (uint16_t)((uint32_t)x * 32767u / 65535u);
  const uint16_t ly = (uint16_t)((uint32_t)y * 32767u / 65535u);
  uint8_t report[5];
  report[0] = buttons;  // bit0 tip, bit1 in-range
  report[1] = (uint8_t)(lx & 0xFF);
  report[2] = (uint8_t)(lx >> 8);
  report[3] = (uint8_t)(ly & 0xFF);
  report[4] = (uint8_t)(ly >> 8);
  _input->setValue(report, sizeof(report));
  _input->notify();
}

void BleHidPen::moveTo(uint16_t x, uint16_t y, bool tipDown) {
  _x = x;
  _y = y;
  // In Range always set while hovering/moving; tip optional.
  const uint8_t buttons = (uint8_t)((tipDown ? 0x01 : 0x00) | 0x02);
  sendReport(buttons, _x, _y);
}

void BleHidPen::clickAt(uint16_t x, uint16_t y) {
  _x = x;
  _y = y;
  sendReport(0x03, _x, _y);  // tip + in range
  delay(12);
  sendReport(0x02, _x, _y);  // tip up, still in range
}

void BleHidPen::click() {
  clickAt(_x, _y);
}

void BleHidPen::lift() {
  // Tip up, out of range, same X/Y — Windows keeps the pointer where it was.
  sendReport(0x00, _x, _y);
}
