// Vraca EEPROM u stanje koje ocekuje stari program:
// adresa 0 = boja (1 = crvena), adresa 1 = svjetlina (0-100), ostalo obrisano (0xFF)
#include <EEPROM.h>

void setup() {
  Serial.begin(9600);
  for (int i = 2; i < EEPROM.length(); i++) EEPROM.update(i, 0xFF);
  EEPROM.update(0, 1);
  EEPROM.update(1, 50);
  Serial.print(F("EEPROM gotov: [0]="));
  Serial.print(EEPROM.read(0));
  Serial.print(F(" [1]="));
  Serial.print(EEPROM.read(1));
  Serial.print(F(" [2]="));
  Serial.println(EEPROM.read(2));
}

void loop() {}
