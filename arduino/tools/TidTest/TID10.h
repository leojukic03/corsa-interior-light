/*
  Driver za Opel/Vauxhall Triple Info Display (TID), verzija s 10 znakova
  (Astra G, Corsa C). Nastao iz TID12_10 biblioteke:
  - buffer je sada stvarno 10 znakova (stari je bio char[9])
  - sve cekalice imaju timeout, pa Arduino ne moze zapeti ako TID ne odgovara

  Spajanje (12-pinski konektor na TID-u):
   1 -> +12 V (15)      6 -> masa (spoji i masu Arduina!)
   2 -> +12 V (AA)     10 -> SCL
   3 -> +12 V (30)     11 -> SDA
                       12 -> MRQ

  Simboli (setSymbol):
   1 DolbyC  2 DolbyB  3 AS  4 Cr  5 stereo  6 CD
   7 RDS     8 CPS     9 []  10 TP  11 .
*/
#pragma once
#include <Arduino.h>

class TID10 {
public:
  TID10(byte sda, byte scl, byte mrq);
  void begin();
  bool show(const char *text10);   // salje tocno 10 znakova + simbole
  void setSymbol(byte symbo, bool on);
  void setBargraph(byte bars);     // 0..10 simbola gore, slijeva nadesno
  void clearSymbols();
  bool ok() const { return _ok; }
  void setSlow(byte k) { _k = k ? k : 1; }  // 1 = normalno, 3 = tri puta sporije
  // dijagnostika: gdje je zadnji put zapelo (0 = nije), i koji bajt
  byte failStep() const { return _failStep; }
  byte failByte() const { return _failByte; }
  void probe(char *out, byte size);  // razine linija bez i s pull-upom

private:
  bool waitLevel(byte pin, byte level, unsigned long timeoutUs);
  bool sendByte(byte data);
  bool sendData(byte data);
  bool startFrame();
  void stopFrame();
  void release();

  byte _sda, _scl, _mrq;
  byte _sym1 = 0, _sym2 = 0;
  bool _ok = true;
  byte _failStep = 0, _failByte = 0, _byteNo = 0;
  byte _k = 1;
public:
  uint16_t _parityErr = 0;  // bit n = bajt n imao gresku pariteta (0 = adresa, 1-3 simboli, 4-13 znakovi)
  bool _od = false;         // true = open-drain + cekanje SCL + ponavljanje bajta
  byte _retries = 0;
private:
  bool sendByteOD(byte data, bool &parityOk);
  void wait(unsigned int us) { for (byte i = 0; i < _k; i++) delayMicroseconds(us); }
};
