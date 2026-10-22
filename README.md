# Spawner Market – Spawnerhandel in Minecraft (Fabric 1.21.11)

Spawner kaufen und verkaufen direkt im Spiel – mit derselben Liquid-Glass-Oberfläche wie der
Liquid Player. **Reine Client-Mod:** Der Server braucht nichts, alles läuft über ein öffentliches
Relay (MQTT, `broker.emqx.io`) zwischen allen Spielern mit der Mod.

## Installation

1. Fabric Loader und **Fabric API** für 1.21.11 installieren.
2. `build/libs/spawner-market-1.1.0.jar` in den `mods`-Ordner kopieren.
3. Minecraft starten, einem Server beitreten, **H** drücken.

## Aufbau

| Links: **Trade-Chats** | Mitte: **Spawnerhandel** | Rechts: **Vouches** |
|---|---|---|
| ein Chat pro Trade | Markt · Deals · Preise | Bewertungen des gewählten Spielers |

### Mitte – Spawnerhandel
- **Markt:** alle Angebote (Verkauf/Ankauf), filterbar nach Seite, Spawner-Typ und Server
  („Mein Server“ / „Alle Server“). Grüner Punkt = Anbieter ist online.
  - Fremdes Angebot (Maus drüber): **Handeln** öffnet einen Trade-Chat, **TPA** schickt `/tpa <Name>`.
  - Eigenes Angebot: **✕** löscht das Angebot.
- **+ Angebot erstellen:** Verkaufen/Kaufen, Spawner (Kuh, Golem, Spinne, Piglin, Skelett,
  Creeper, Lohe), Anzahl, Preis pro Stück
  (`250k`, `1,5m`, `2.000.000`), Notiz. Zeigt den aktuellen Marktpreis zur Orientierung.
- **Deals:** abgeschlossene Handel der letzten 30 Tage (Verkäufer → Käufer, Preis). Deals kann
  man nicht eintippen – sie entstehen nur durch einen Vouch im Trade (siehe unten).
- **Preise:** pro Spawner-Typ Ø-Preis aus den Deals (nach Stückzahl gewichtet), günstigstes
  Verkaufsangebot und höchstes Ankaufsangebot. Klick zeigt die passenden Angebote.

### Links – Trade-Chats
- Jeder Trade hat seinen eigenen Chat: **Handeln** an einem Angebot öffnet ihn, beim Anbieter
  taucht er automatisch auf. Oben steht, um welches Angebot es geht.
- **Positiver Vouch** (rechts) an den Handelspartner **schließt den Deal ab**: Er wird
  eingetragen, das Angebot verschwindet, und der Chat wird bei beiden gelöscht. Ein neuer
  Deal bekommt einen neuen Chat.
- **Negativer Vouch** oder **✕** beendet den Trade ohne Deal (Chat wird gelöscht).
- **Namen laufen automatisch mit:** Die Namen im Deal kommen aus den Minecraft-Konten der beiden
  Trade-Partner, nicht aus einem Textfeld. Ein Deal zählt erst, wenn **beide** Clients ihn
  bestätigt haben; das passiert automatisch, sobald einer vouched und beide im Chat geschrieben
  haben.
- TPA-Anfragen von anderen Mod-Spielern erscheinen oben mit **Ja** (schickt `/tpaccept <Name>`).

### Rechts – Vouches
- Klick auf ein Angebot, einen Deal oder einen Namen zeigt die Vouches dieses Spielers
  (+ / −, Text, Datum) und ob er gerade online ist und wo.
- Unten Text eingeben und **+ Positiv** oder **− Negativ** klicken. Pro Spieler zählt ein Vouch
  von dir – ein neuer ersetzt den alten, **✕** an deinem Vouch löscht ihn.
- Ohne Auswahl: Rangliste der Top-Händler. Dein Kopf oben rechts zeigt deine eigenen Vouches.

### Zahnrad
Farbthema, Black Mode, Benachrichtigungen an/aus, Deckkraft.

### Benachrichtigungen
Kleine Banner oben im Bild (mit Ton), wenn jemand ein **neues Angebot** aufmacht: Spawner, Anzahl,
Preis pro Stück, Verkauf/Ankauf, von wem und wie der Preis zum Schnitt der Deals steht. Dazu
neue Trades an deinen Angeboten, Trade-Nachrichten, bestätigte/beendete Trades und TPA-Anfragen.
Unter **Zahnrad → Filter & Anzeige** ist alles einstellbar:
- **Angebote:** Verkauf/Ankauf, nur dein Server, nur gute Preise, nur bestimmte Spawner.
- **Trades:** neue Trades, Nachrichten, Deal/Trade beendet, TPA, alle neuen Deals auf dem Markt.
- **Anzeige:** Position (links/Mitte/rechts), Dauer, Ton, auch über anderen Menüs. **Test** zeigt Beispiele.

Solange das Spawnerhandel-Menü offen ist, kommen keine Banner – es zeigt alles selbst.

## Einstellungen

`config/spawner-market.json`:
- `tpaCommand` / `tpaAcceptCommand`: falls dein Server andere Befehle hat (Standard `tpa` / `tpaccept`).
- `published`: deine Angebote, Deals und Vouches; sie werden nach jedem Verbinden neu gesendet.
- `trades`: deine offenen Trade-Chats.

## Wichtig zu wissen

- Das Relay ist öffentlich und ohne Login. Die Mod prüft, dass jeder nur unter seiner eigenen UUID
  schreibt, aber das Relay selbst kennt keine Konten: **Wer das Protokoll gezielt nachbaut, kann
  Angebote, Vouches und (mit mehr Aufwand) auch Deals fälschen.** Mit der normalen Mod geht das
  nicht. Wirklich fälschungssicher wäre es nur mit einem eigenen Server mit Login.
  Vouches und Preise sind ein Anhaltspunkt, kein Beweis – handle trotzdem vorsichtig.
- Angebote verschwinden nach 7 Tagen, Deals zählen 30 Tage. Ist dein Partner offline, bekommt er
  beim nächsten Start nur deine letzte Nachricht.
- Trade-Chats stehen in der Reihenfolge, in der die Nachrichten ankommen – die Uhren der Spieler
  gehen nie genau gleich, nach Absenderzeit sortiert landeten Antworten sonst über der Frage.
- Preise sind in der Währung des jeweiligen Servers – darum filtert der Markt standardmäßig auf
  deinen Server.

## Bauen

```
gradlew build
```

Selbsttest im echten Spiel (ohne Relay, Ergebnis und Screenshots in `run-prod/`; die Welt muss
unter `run-prod/saves` liegen):

```
gradlew prodClient -Pselftest -Pworld="New World"
```
