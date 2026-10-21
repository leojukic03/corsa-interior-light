/*
  TidTest - salje "LEO" na TID svake 1 s i za svaki bajt cita
  potvrdu pariteta od TID-a (vidi Tid-protokoll.pdf, str. 5 i 6).
  Ispis: P=bajtovi s greskom (0=adresa, 1-3 simboli, 4-13 znakovi), '.' = dobar, 'X' = greska
  SDA=D7, SCL=D5, MRQ=D6 (kao stari kod).
*/
#include "TID10.h"

TID10 tid(7, 5, 6);
const char TEXT[10] = {' ', ' ', ' ', 'L', 'E', 'O', ' ', ' ', ' ', ' '};

void setup() {
  Serial.begin(9600);
  pinMode(LED_BUILTIN, OUTPUT);
  tid.begin();
  Serial.println(F("HELLO TidTest3"));
}

// napon napajanja Una u mV (mjeri unutarnju referencu 1.1 V prema Vcc)
long readVcc() {
  ADMUX = _BV(REFS0) | _BV(MUX3) | _BV(MUX2) | _BV(MUX1);
  delay(2);
  ADCSRA |= _BV(ADSC);
  while (bit_is_set(ADCSRA, ADSC)) {}
  long r = ADCL;
  r |= ADCH << 8;
  return 1125300L / r;
}

void loop() {
  digitalWrite(LED_BUILTIN, !digitalRead(LED_BUILTIN));
  Serial.print(F("Vcc="));
  Serial.print(readVcc());
  Serial.print(F("mV  "));
  static bool od = true;
  od = false;  // samo stari nacin, bez prekidanja poruke (cisti test nakon reseta TID-a)
  tid._od = od;
  bool ok = tid.show(TEXT);
  Serial.print(od ? F("[OD]   ") : F("[STARI]"));
  Serial.print(ok ? F(" poslano  P=") : F(" PALO     P="));
  for (byte i = 0; i < 14; i++) Serial.print(bitRead(tid._parityErr, i) ? 'X' : '.');
  if (od) {
    Serial.print(F(" ponovljeno="));
    Serial.print(tid._retries);
  }
  if (!ok) {
    Serial.print(F("  korak="));
    Serial.print(tid.failStep());
    Serial.print(F(" bajt="));
    Serial.print(tid.failByte());
  }
  Serial.println();
  delay(1000);
}
