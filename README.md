# Spawner Market – Spawnerhandel in Minecraft (Fabric 1.21.11)

Spawner kaufen und verkaufen direkt im Spiel – mit derselben Liquid-Glass-Oberfläche wie der
Liquid Player. **Reine Client-Mod:** Der Server braucht nichts, alles läuft über ein öffentliches
Relay (MQTT, `broker.emqx.io`) zwischen allen Spielern mit der Mod.

## Installation

1. Fabric Loader und **Fabric API** für 1.21.11 installieren.
2. `build/libs/spawner-market-1.2.0.jar` in den `mods`-Ordner kopieren.
3. Minecraft starten, einem Server beitreten, **H** drücken.

## Aufbau

| Links: **Trade-Chats** | Mitte: **Spawnerhandel** | Rechts: **Vouches** |
|---|---|---|
| ein Chat pro Trade | Markt · Deals · Preise | Bewertungen des gewählten Spielers |

### Mitte – Spawnerhandel
- **Markt:** alle Angebote auf deinem Server, höchster Preis zuerst. Filter: Alle / Verkauf /
  Ankauf und **Meine Gebote** (alle deine eigenen Angebote, auf jedem Server).
  Grüner Punkt = Anbieter ist online.
- **Spawner-Filter:** unter den Tabs eine Reihe mit den Spawner-Modellen – Klick zeigt nur diesen
  Spawner (in Markt, Deals und Preise), nochmal klicken = alle.
  - Fremdes Angebot (Maus drüber): **Handeln** öffnet einen Trade-Chat, **TPA** schickt `/tpa <Name>`.
  - Eigenes Angebot: **✕** löscht das Angebot.
- **+ Angebot erstellen:** Verkaufen/Kaufen, Spawner (Kuh, Golem, Spinne, Piglin, Skelett,
  Creeper, Lohe), Anzahl, Preis pro Stück
  (`250k`, `1,5m`, `2.000.000`), Notiz. Zeigt den aktuellen Marktpreis zur Orientierung.
- **Deals:** abgeschlossene Handel der letzten 30 Tage (Verkäufer → Käufer, Preis). Deals kann
  man nicht eintippen – sie entstehen nur durch einen Vouch im Trade (siehe unten).
- **Preise:** pro Spawner der höchste Ankaufs- und der höchste Verkaufspreis, dazu der Ø-Preis
  aus den Deals (nach Stückzahl gewichtet). Klick zeigt die passenden Angebote.

### Links – Trade-Chats
- Jeder Trade hat seinen eigenen Chat: **Handeln** an einem Angebot öffnet ihn, beim Anbieter
  taucht er automatisch auf. Oben steht, um welches Angebot es geht.
- Die erste Nachricht steht oben, neue kommen darunter.
- **+ Deal** (positiver Vouch, rechts) **schließt den Deal ab**: Er wird eingetragen, das Angebot
  verschwindet, und der Chat wird bei beiden gelöscht. Ein neuer Deal bekommt einen neuen Chat.
- **○ Neutral:** Der Trade kam nicht zustande – kein Deal, Trade-Chat und Angebot bleiben.
- **− Negativ** oder **✕** beendet den Trade ohne Deal (Chat wird gelöscht).
- **Namen laufen automatisch mit:** Die Namen im Deal kommen aus den Minecraft-Konten der beiden
  Trade-Partner, nicht aus einem Textfeld. Ein Deal zählt erst, wenn **beide** Clients ihn
  bestätigt haben; das passiert automatisch, sobald einer vouched und beide im Chat geschrieben
  haben.
- TPA-Anfragen von anderen Mod-Spielern erscheinen oben mit **Ja** (schickt `/tpaccept <Name>`).

### Rechts – Vouches
- Klick auf ein Angebot, einen Deal oder einen Namen zeigt die Vouches dieses Spielers
  (+ / −, Text, Datum) und ob er gerade online ist und wo.
- **Vouchen geht nur nach einem Trade** – also nur für den Partner eines offenen Trade-Chats.
  Text eingeben und **+ Deal**, **○ Neutral** oder **− Neg.** klicken.
- Vouches stacken nicht: Pro Person zählt nur ihr neuester Vouch. Ein positiver Vouch zählt erst,
  wenn beide Seiten den Deal bestätigt haben (vorher „unbestätigt“).
- Ohne Auswahl: Rangliste der Top-Händler. Dein Kopf oben rechts zeigt deine eigenen Vouches.

### Zahnrad
Farbthema, Black Mode, Benachrichtigungen (Toast + Ton bei Trade-Nachricht/TPA), Deckkraft und
der **Preiswecker**: pro Spawner einen Preis eintragen, dazu **Kaufen ≤** (Meldung, wenn jemand
zu diesem Preis oder billiger verkauft) oder **Verkaufen ≥** (wenn jemand so viel oder mehr
zahlt). Gilt für Angebote auf deinem aktuellen Server.

## Einstellungen

`config/spawner-market.json`:
- `tpaCommand` / `tpaAcceptCommand`: falls dein Server andere Befehle hat (Standard `tpa` / `tpaccept`).
- `published`: deine Angebote, Deals und Vouches; sie werden nach jedem Verbinden neu gesendet.
- `trades`: deine offenen Trade-Chats.
- `alarms`: deine Preiswecker.

## Wichtig zu wissen

- Das Relay ist öffentlich und ohne Login. Die Mod prüft, dass jeder nur unter seiner eigenen UUID
  schreibt, aber das Relay selbst kennt keine Konten: **Wer das Protokoll gezielt nachbaut, kann
  Angebote, Vouches und (mit mehr Aufwand) auch Deals fälschen.** Mit der normalen Mod geht das
  nicht. Wirklich fälschungssicher wäre es nur mit einem eigenen Server mit Login.
  Vouches und Preise sind ein Anhaltspunkt, kein Beweis – handle trotzdem vorsichtig.
- Angebote verschwinden nach 7 Tagen, Deals zählen 30 Tage. Ist dein Partner offline, bekommt er
  beim nächsten Start nur deine letzte Nachricht.
- Preise sind in der Währung des jeweiligen Servers – darum zeigt der Markt deinen Server
  (bist du auf keinem, alle Server).

## Bauen

```
gradlew build
```
