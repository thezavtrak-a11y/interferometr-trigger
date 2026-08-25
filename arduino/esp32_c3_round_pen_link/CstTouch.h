#pragma once
#include <stdint.h>

struct CstTouch {
  bool down;
  uint8_t fingers;
  uint8_t gesture;
  int x1, y1;
  int x2, y2;
};
