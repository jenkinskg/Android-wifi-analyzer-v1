#include <Arduino.h>
#include <SPI.h>
#include <Adafruit_GFX.h>
#include <Adafruit_ILI9341.h>
#include "driver/twai.h"

// Volvo XC60 CYD Gauge
// Target: ESP32-2432S028 2-USB Cheap Yellow Display
// Launcher-compatible application image build
//
// Display: ILI9341-compatible panel, inverted on the 2-USB CYD revision.
// CAN: Waveshare SN65HVD230 transceiver using the ESP32 TWAI controller.
//
// CYD -> SN65HVD230:
//   GPIO27 -> TXD
//   GPIO35 <- RXD
//   3V3    -> VCC
//   GND    -> GND
//   CAN H  -> OBD-II pin 6
//   CAN L  -> OBD-II pin 14
//   GND    -> OBD-II pin 4 or 5

static constexpr int TFT_SCLK = 14;
static constexpr int TFT_MISO = 12;
static constexpr int TFT_MOSI = 13;
static constexpr int TFT_CS   = 15;
static constexpr int TFT_DC   = 2;
static constexpr int TFT_RST  = -1;
static constexpr int TFT_BL   = 21;

static constexpr gpio_num_t CAN_TX = GPIO_NUM_27;
static constexpr gpio_num_t CAN_RX = GPIO_NUM_35;

SPIClass tftSpi(HSPI);
Adafruit_ILI9341 tft(&tftSpi, TFT_DC, TFT_CS, TFT_RST);

static bool canReady = false;
static float moduleVoltage = NAN;
static float coolantC = NAN;
static uint32_t lastGoodVoltageMs = 0;
static uint32_t lastGoodCoolantMs = 0;
static uint32_t lastUpdateMs = 0;
static constexpr uint32_t UPDATE_PERIOD_MS = 5000;
static constexpr uint32_t DATA_STALE_MS = 15000;

static uint16_t rgb565(uint8_t r, uint8_t g, uint8_t b) {
  return tft.color565(r, g, b);
}

static void centerText(const String &s, int16_t y, uint8_t size, uint16_t color) {
  int16_t x1, y1;
  uint16_t w, h;
  tft.setTextSize(size);
  tft.setTextColor(color);
  tft.getTextBounds(s, 0, y, &x1, &y1, &w, &h);
  int16_t x = max<int16_t>(0, (tft.width() - (int16_t)w) / 2);
  tft.setCursor(x, y);
  tft.print(s);
}

static void drawStaticUi() {
  tft.fillScreen(ILI9341_BLACK);
  tft.drawFastHLine(0, 28, 320, rgb565(70, 70, 70));
  centerText("2011 VOLVO XC60", 5, 2, ILI9341_WHITE);

  tft.drawRoundRect(8, 38, 146, 126, 10, rgb565(90, 90, 90));
  tft.drawRoundRect(166, 38, 146, 126, 10, rgb565(90, 90, 90));

  centerText("SYSTEM VOLTAGE", 44, 1, rgb565(180, 180, 180));
  tft.setCursor(191, 44);
  tft.setTextSize(1);
  tft.setTextColor(rgb565(180, 180, 180));
  tft.print("COOLANT");

  tft.drawRoundRect(8, 174, 304, 58, 10, rgb565(90, 90, 90));
}

static String voltageState() {
  if (isnan(moduleVoltage)) return "NO DATA";
  if (moduleVoltage >= 13.20f) return "CHARGING";
  if (moduleVoltage >= 12.00f) return "NOT CHARGING";
  return "LOW VOLTAGE";
}

static uint16_t voltageColor() {
  if (isnan(moduleVoltage)) return ILI9341_YELLOW;
  if (moduleVoltage >= 13.20f) return ILI9341_GREEN;
  if (moduleVoltage >= 12.00f) return ILI9341_CYAN;
  return ILI9341_RED;
}

static uint16_t coolantColor() {
  if (isnan(coolantC)) return ILI9341_YELLOW;
  if (coolantC >= 110.0f) return ILI9341_RED;       // 230 F
  if (coolantC >= 102.0f) return rgb565(255, 165, 0);
  return ILI9341_GREEN;
}

static void drawValues() {
  tft.fillRect(12, 60, 138, 100, ILI9341_BLACK);
  tft.fillRect(170, 60, 138, 100, ILI9341_BLACK);
  tft.fillRect(12, 178, 296, 50, ILI9341_BLACK);

  if (isnan(moduleVoltage)) {
    tft.setCursor(27, 78);
    tft.setTextSize(3);
    tft.setTextColor(ILI9341_YELLOW);
    tft.print("--.-- V");
  } else {
    char vb[16];
    snprintf(vb, sizeof(vb), "%.2f V", moduleVoltage);
    tft.setCursor(23, 78);
    tft.setTextSize(3);
    tft.setTextColor(voltageColor());
    tft.print(vb);
  }

  String vs = voltageState();
  int16_t x1, y1;
  uint16_t w, h;
  tft.setTextSize(2);
  tft.getTextBounds(vs, 0, 0, &x1, &y1, &w, &h);
  tft.setCursor(81 - (int16_t)w / 2, 124);
  tft.setTextColor(voltageColor());
  tft.print(vs);

  if (isnan(coolantC)) {
    tft.setCursor(186, 78);
    tft.setTextSize(3);
    tft.setTextColor(ILI9341_YELLOW);
    tft.print("--");
    tft.setCursor(209, 124);
    tft.setTextSize(2);
    tft.print("NO DATA");
  } else {
    float f = coolantC * 9.0f / 5.0f + 32.0f;
    char fb[16];
    char cb[16];
    snprintf(fb, sizeof(fb), "%.0f F", f);
    snprintf(cb, sizeof(cb), "%.0f C", coolantC);
    tft.setCursor(187, 75);
    tft.setTextSize(3);
    tft.setTextColor(coolantColor());
    tft.print(fb);
    tft.setCursor(208, 120);
    tft.setTextSize(2);
    tft.print(cb);
  }

  String status;
  uint16_t sc = ILI9341_WHITE;
  if (!canReady) {
    status = "CAN NOT READY";
    sc = ILI9341_RED;
  } else if (isnan(moduleVoltage) && isnan(coolantC)) {
    status = "WAITING FOR OBD-II DATA";
    sc = ILI9341_YELLOW;
  } else if ((!isnan(moduleVoltage) && moduleVoltage < 12.0f) ||
             (!isnan(coolantC) && coolantC >= 110.0f)) {
    status = "WARNING";
    sc = ILI9341_RED;
  } else {
    status = "LIVE CAN - UPDATE 5 SEC";
    sc = ILI9341_GREEN;
  }

  tft.setTextSize(2);
  tft.getTextBounds(status, 0, 0, &x1, &y1, &w, &h);
  tft.setCursor(160 - (int16_t)w / 2, 194);
  tft.setTextColor(sc);
  tft.print(status);

  tft.setTextSize(1);
  tft.setTextColor(rgb565(140, 140, 140));
  tft.setCursor(47, 216);
  tft.print("PID 42 voltage   PID 05 coolant");
}

static bool initCan() {
  twai_general_config_t g_config = TWAI_GENERAL_CONFIG_DEFAULT(CAN_TX, CAN_RX, TWAI_MODE_NORMAL);
  twai_timing_config_t t_config = TWAI_TIMING_CONFIG_500KBITS();
  twai_filter_config_t f_config = TWAI_FILTER_CONFIG_ACCEPT_ALL();

  esp_err_t err = twai_driver_install(&g_config, &t_config, &f_config);
  if (err != ESP_OK) {
    Serial.printf("TWAI install failed: %d\n", (int)err);
    return false;
  }

  err = twai_start();
  if (err != ESP_OK) {
    Serial.printf("TWAI start failed: %d\n", (int)err);
    twai_driver_uninstall();
    return false;
  }

  Serial.println("TWAI ready at 500 kbit/s");
  return true;
}

static void drainRx() {
  twai_message_t rx;
  while (twai_receive(&rx, 0) == ESP_OK) {
  }
}

static bool requestPid(uint8_t pid, uint8_t *payload, size_t &payloadLen) {
  payloadLen = 0;
  if (!canReady) return false;

  drainRx();

  twai_message_t tx = {};
  tx.identifier = 0x7DF;
  tx.extd = 0;
  tx.rtr = 0;
  tx.data_length_code = 8;
  tx.data[0] = 0x02;
  tx.data[1] = 0x01;
  tx.data[2] = pid;
  tx.data[3] = 0x00;
  tx.data[4] = 0x00;
  tx.data[5] = 0x00;
  tx.data[6] = 0x00;
  tx.data[7] = 0x00;

  if (twai_transmit(&tx, pdMS_TO_TICKS(100)) != ESP_OK) {
    Serial.printf("PID %02X transmit failed\n", pid);
    return false;
  }

  uint32_t deadline = millis() + 700;
  while ((int32_t)(deadline - millis()) > 0) {
    twai_message_t rx = {};
    if (twai_receive(&rx, pdMS_TO_TICKS(50)) != ESP_OK) continue;
    if (rx.extd || rx.rtr) continue;
    if (rx.identifier < 0x7E8 || rx.identifier > 0x7EF) continue;
    if (rx.data_length_code < 4) continue;
    if (rx.data[1] != 0x41 || rx.data[2] != pid) continue;

    uint8_t obdBytes = rx.data[0];
    if (obdBytes < 3) continue;
    size_t available = min<size_t>(rx.data_length_code - 3, obdBytes - 2);
    payloadLen = min<size_t>(available, 5);
    for (size_t i = 0; i < payloadLen; ++i) {
      payload[i] = rx.data[3 + i];
    }
    return true;
  }

  return false;
}

static void pollObd() {
  uint8_t p[5];
  size_t n = 0;

  if (requestPid(0x42, p, n) && n >= 2) {
    moduleVoltage = ((uint16_t)p[0] * 256u + p[1]) / 1000.0f;
    lastGoodVoltageMs = millis();
    Serial.printf("Voltage: %.3f V\n", moduleVoltage);
  } else {
    Serial.println("Voltage PID 42: no response");
  }

  delay(100);

  if (requestPid(0x05, p, n) && n >= 1) {
    coolantC = (float)p[0] - 40.0f;
    lastGoodCoolantMs = millis();
    Serial.printf("Coolant: %.1f C\n", coolantC);
  } else {
    Serial.println("Coolant PID 05: no response");
  }

  uint32_t now = millis();
  if (lastGoodVoltageMs == 0 || now - lastGoodVoltageMs > DATA_STALE_MS) moduleVoltage = NAN;
  if (lastGoodCoolantMs == 0 || now - lastGoodCoolantMs > DATA_STALE_MS) coolantC = NAN;

  drawValues();
}

void setup() {
  Serial.begin(115200);
  delay(200);

  pinMode(TFT_BL, OUTPUT);
  digitalWrite(TFT_BL, HIGH);

  tftSpi.begin(TFT_SCLK, TFT_MISO, TFT_MOSI, TFT_CS);
  tft.begin(40000000);
  tft.setRotation(1);
  tft.invertDisplay(true);  // CYD-2-USB Launcher target uses inverted panel
  tft.setTextWrap(false);

  drawStaticUi();
  tft.setCursor(62, 194);
  tft.setTextSize(2);
  tft.setTextColor(ILI9341_YELLOW);
  tft.print("STARTING CAN...");

  canReady = initCan();
  drawValues();

  lastUpdateMs = millis() - UPDATE_PERIOD_MS;
}

void loop() {
  uint32_t now = millis();
  if (now - lastUpdateMs >= UPDATE_PERIOD_MS) {
    lastUpdateMs = now;
    pollObd();
  }
  delay(10);
}
