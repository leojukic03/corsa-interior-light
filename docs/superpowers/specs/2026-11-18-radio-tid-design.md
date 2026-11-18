# Corsa Radio na TID-u

## Zašto
Dok svira radio, gornji ekran (TID) ne pokazuje ništa o stanici. Leo želi na njemu ime stanice, a kad se nešto dogodi (AF prebaci frekvenciju, promjena banda) i kratku poruku.

## Pristupi
1. **Dvije aplikacije koje razgovaraju** (odabrano). Radio objavi MediaSession; Interior Light je već čita i šalje na TID. Za kratke poruke radio pošalje broadcast, a Interior Light ga proslijedi Arduinu kao `L<tekst>` (2 s).
2. Spajanje radija u Interior Light: jedna instalacija i zajednički dizajn, ali velik posao (dva sučelja, radio s grane `radio` na `main`). Odbijeno zasad.
3. Samo broadcast bez MediaSessiona: radi, ali bez tipki na volanu i prikaza u sustavu headunita. Slabije od 1.

## Što se vidi na TID-u
```
svira, RDS ima ime:   HRT-HR2          (naslov sesije = PS ime)
svira, bez RDS-a:     FM 98.50         (naslov = frekvencija)
AF prebacio:          AF 100.20        (2 s, pa natrag na ime)
promjena banda:       FM2 / AM         (2 s)
radio ugašen:         idle tekst (OPEL CORSA)
```
RadioText ide u podnaslov sesije, ali se ne prikazuje na TID-u: Interior Light za radio aplikacije prikazuje samo naslov (`Hub.onMedia`, `isRadio`), a RadioText se mijenja prečesto za 10 znakova. Postavka "Show station" u Interior Lightu i dalje uključuje/isključuje prikaz.

## Radio (grana `radio`)
- MediaSession u `Tuner`-u: postoji dok radio svira (i u pozadini), metadata title = PS ili frekvencija, subtitle = RadioText; state PLAYING / STOPPED kod `powerOff`.
- Tipke medija iz sesije (sljedeća/prethodna) = sljedeća/prethodna spremljena stanica, kao tipke na volanu.
- Broadcast `hr.leo.corsalight.TID_MESSAGE` s tekstom, `setPackage("hr.leo.corsalight")`: kod AF prebacivanja i promjene banda.

## Interior Light (`main`, 3.0.6)
- `LinkService` prima `TID_MESSAGE` (registriran u kodu, kao ostali), tekst očisti `TextUtil.forTid`, najviše 10 znakova, pošalje `L<tekst>`.
- Ništa drugo: sesiju radija već čita `MediaListener`.

## Nije u opsegu
Spajanje aplikacija, RadioText na TID-u, PTY, logotipi na TID-u.

## Provjera
Radio u demo načinu na emulatoru objavljuje sesiju (vidi se u `dumpsys media_session`); broadcast se vidi u logu Interior Lighta. Pravi TID tek u autu.
