#include "TID10.h"

// Iste vrijednosti kao u originalnoj TID12_10 biblioteci (rade na Nano/ATmega328p)
#define TID_DELAY 150
#define DELAY_300 150
#define DELAY_100 100
// Na pocetku (TID jos nije prihvatio poruku) smijemo brzo odustati.
// Kad poruka jednom krene, NE prekidamo je: prekid usred poruke zaglavi TID
// sve dok mu ne nestane struje (ima trajni +30, pa ga ni kontakt ne resetira).
#define TID_START_TIMEOUT_US 100000UL   // 100 ms
#define TID_FRAME_TIMEOUT_US 5000000UL  // 5 s po koraku unutar poruke (ispravan TID odgovori za < 1 ms)

TID10::TID10(byte sda, byte scl, byte mrq) : _sda(sda), _scl(scl), _mrq(mrq) {}

void TID10::begin() {
  release();
}

void TID10::release() {
  pinMode(_mrq, OUTPUT);
  pinMode(_sda, OUTPUT);
  pinMode(_scl, OUTPUT);
  digitalWrite(_mrq, HIGH);
  digitalWrite(_sda, HIGH);
  digitalWrite(_scl, HIGH);
}

bool TID10::waitLevel(byte pin, byte level, unsigned long timeoutUs) {
  unsigned long t = micros();
  while (digitalRead(pin) != level) {
    if (micros() - t > timeoutUs) return false;
  }
  return true;
}

bool TID10::sendByte(byte data) {
  pinMode(_scl, OUTPUT);
  for (uint8_t m = 0x80; m != 0; m >>= 1) {
    digitalWrite(_sda, (data & m) ? HIGH : LOW);
    delayMicroseconds(5);
    digitalWrite(_scl, HIGH);
    delayMicroseconds(50);
    digitalWrite(_scl, LOW);
    delayMicroseconds(50);
  }
  delayMicroseconds(DELAY_300);
  pinMode(_sda, INPUT);
  delayMicroseconds(TID_DELAY);
  pinMode(_scl, INPUT);
  delayMicroseconds(DELAY_100);
  if (!waitLevel(_scl, HIGH, TID_FRAME_TIMEOUT_US)) { _failStep = 3; _failByte = _byteNo; return false; }
  // Tid-protokoll.pdf str. 5: TID drzi SDA LOW ako je paritet dobar. Samo brojimo, za dijagnostiku
  // (vise gresaka = TID pokaze prazno; najcesce nema zajednicke mase).
  if (digitalRead(_sda) == HIGH) _parityErrs++;
  delayMicroseconds(DELAY_300);
  pinMode(_scl, OUTPUT);
  digitalWrite(_scl, LOW);
  if (!waitLevel(_sda, HIGH, TID_FRAME_TIMEOUT_US)) { _failStep = 4; _failByte = _byteNo; return false; }
  pinMode(_sda, OUTPUT);
  _byteNo++;
  return true;
}

bool TID10::sendData(byte data) {
  // neparni paritet (Claas Anders "CaScAdE" Rathje, mylifesucks.de)
  byte val = data;
  val ^= val >> 4;
  val ^= val >> 2;
  val ^= val >> 1;
  byte pari = !(val & 0x01);
  return sendByte((data << 1) | pari);
}

bool TID10::startFrame() {
  pinMode(_sda, INPUT);
  digitalWrite(_mrq, LOW);
  delayMicroseconds(100);
  if (!waitLevel(_sda, LOW, TID_START_TIMEOUT_US)) { _failStep = 1; return false; }
  digitalWrite(_mrq, HIGH);
  delayMicroseconds(100);
  if (!waitLevel(_sda, HIGH, TID_FRAME_TIMEOUT_US)) { _failStep = 2; return false; }
  pinMode(_sda, OUTPUT);
  pinMode(_scl, OUTPUT);
  digitalWrite(_sda, LOW);
  delayMicroseconds(100);
  digitalWrite(_scl, LOW);
  delayMicroseconds(100);
  return true;
}

void TID10::stopFrame() {
  digitalWrite(_sda, LOW);
  delayMicroseconds(100);
  digitalWrite(_mrq, HIGH);
  delayMicroseconds(500);
  digitalWrite(_scl, HIGH);
  delayMicroseconds(100);
  digitalWrite(_sda, HIGH);
  delayMicroseconds(100);
}

void TID10::probe(char *out, byte size) {
  // prvo spusti linije, da ne citamo naboj koji je ostao od Arduina
  pinMode(_sda, OUTPUT);
  pinMode(_scl, OUTPUT);
  pinMode(_mrq, OUTPUT);
  digitalWrite(_sda, LOW);
  digitalWrite(_scl, LOW);
  digitalWrite(_mrq, LOW);
  delayMicroseconds(50);
  pinMode(_sda, INPUT);
  pinMode(_scl, INPUT);
  pinMode(_mrq, INPUT);
  delayMicroseconds(200);
  byte a = digitalRead(_sda), b = digitalRead(_scl), c = digitalRead(_mrq);
  pinMode(_sda, INPUT_PULLUP);
  pinMode(_scl, INPUT_PULLUP);
  pinMode(_mrq, INPUT_PULLUP);
  delayMicroseconds(200);
  byte d = digitalRead(_sda), e = digitalRead(_scl), f = digitalRead(_mrq);
  release();
  // koliko TID-u treba da spusti SDA nakon sto spustimo MRQ (max 3 s)
  pinMode(_sda, INPUT);
  digitalWrite(_mrq, LOW);
  unsigned long t0 = millis();
  while (digitalRead(_sda) == HIGH && millis() - t0 < 3000) {}
  unsigned long ack = millis() - t0;
  release();
  snprintf(out, size, "T bez pu SDA=%u SCL=%u MRQ=%u | pu SDA=%u SCL=%u MRQ=%u | korak=%u bajt=%u | ack=%lums",
           a, b, c, d, e, f, _failStep, _failByte, ack);
}

bool TID10::show(const char *text10) {
  _failStep = 0;
  _byteNo = 0;
  bool good = startFrame() && sendByte(0x9B);  // adresa 10-znamenkastog TID-a
  if (good) {
    digitalWrite(_mrq, LOW);
    good = sendData(_sym1) && sendData(_sym2) && sendData(_sym2);
    for (byte i = 0; i < 10 && good; i++) good = sendData(text10[i] & 0x7F);
  }
  if (good) stopFrame();
  else release();
  _ok = good;
  return good;
}

void TID10::setSymbol(byte symbo, bool on) {
  switch (symbo) {
    case 9:  bitWrite(_sym1, 0, on); break;
    case 3:  bitWrite(_sym1, 1, on); break;
    case 5:  bitWrite(_sym1, 3, on); break;
    case 10: bitWrite(_sym1, 4, on); break;
    case 7:  bitWrite(_sym1, 5, on); break;
    case 11: bitWrite(_sym1, 6, on); break;
    case 8:  bitWrite(_sym2, 2, on); break;
    case 4:  bitWrite(_sym2, 3, on); break;
    case 2:  bitWrite(_sym2, 4, on); break;
    case 1:  bitWrite(_sym2, 5, on); break;
    case 6:  bitWrite(_sym2, 6, on); break;
  }
}

void TID10::setBargraph(byte bars) {
  if (bars > 10) bars = 10;
  for (byte i = 1; i <= 10; i++) setSymbol(i, i <= bars);
}

void TID10::clearSymbols() {
  _sym1 = 0;
  _sym2 = 0;
}
