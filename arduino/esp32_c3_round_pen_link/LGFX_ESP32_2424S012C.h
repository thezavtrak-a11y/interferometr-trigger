// LovyanGFX config for ESP32-2424S012C
// GC9A01 240x240 round IPS + backlight PWM
// Ref: https://circuitdigest.com/review/inside-the-esp32-2424s012c-development-board-teardown
#pragma once

#define LGFX_USE_V1
#include <LovyanGFX.hpp>

#ifndef PIN_LCD_SCLK
#define PIN_LCD_SCLK 6
#define PIN_LCD_MOSI 7
#define PIN_LCD_DC   2
#define PIN_LCD_CS   10
#define PIN_LCD_BL   3
#endif

class LGFX : public lgfx::LGFX_Device {
  lgfx::Panel_GC9A01 _panel;
  lgfx::Bus_SPI _bus;
  lgfx::Light_PWM _light;

public:
  LGFX() {
    {
      auto c = _bus.config();
      c.spi_host = SPI2_HOST;
      c.spi_mode = 0;
      c.freq_write = 40000000;
      c.freq_read = 16000000;
      c.pin_sclk = PIN_LCD_SCLK;
      c.pin_mosi = PIN_LCD_MOSI;
      c.pin_miso = -1;
      c.pin_dc = PIN_LCD_DC;
      c.dma_channel = SPI_DMA_CH_AUTO;
      _bus.config(c);
      _panel.setBus(&_bus);
    }
    {
      auto c = _panel.config();
      c.pin_cs = PIN_LCD_CS;
      c.pin_rst = -1;  // power-on reset on this board
      c.pin_busy = -1;
      c.panel_width = 240;
      c.panel_height = 240;
      c.memory_width = 240;
      c.memory_height = 240;
      c.readable = false;
      c.invert = true;
      c.rgb_order = false;
      c.bus_shared = false;
      _panel.config(c);
    }
    {
      auto c = _light.config();
      c.pin_bl = PIN_LCD_BL;
      c.invert = false;
      c.freq = 5000;
      c.pwm_channel = 0;
      _light.config(c);
      _panel.setLight(&_light);
    }
    setPanel(&_panel);
  }
};
