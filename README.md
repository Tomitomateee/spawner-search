# Spawner Market – Spawnerhandel in Minecraft (Fabric 1.21.11)

Spawner kaufen und verkaufen direkt im Spiel – mit derselben Liquid-Glass-Oberfläche wie der
Liquid Player. **Reine Client-Mod:** Der Server braucht nichts, alles läuft über ein öffentliches
Relay (MQTT, `broker.emqx.io`) zwischen allen Spielern mit der Mod.

## Installation

1. Fabric Loader und **Fabric API** für 1.21.11 installieren.
2. `build/libs/spawner-market-1.0.0.jar` in den `mods`-Ordner kopieren.
3. Minecraft starten, einem Server beitreten, **H** drücken.

## Aufbau

| Links: **Chats** | Mitte: **Spawnerhandel** | Rechts: **Vouches** |
|---|---|---|
| Global, Server, Privat | Markt · Deals · Preise | Bewertungen des gewählten Spielers |

### Mitte – Spawnerhandel
- **Markt:** alle Angebote (Verkauf/Ankauf), filterbar nach Seite, Spawner-Typ und Server
  („Mein Server“ / „Alle Server“). Grüner Punkt = Anbieter ist online.
  - Fremdes Angebot (Maus drüber): **Chat** öffnet einen Privatchat, **TPA** schickt `/tpa <Name>`.
  - Eigenes Angebot: **✓ Deal** trägt einen abgeschlossenen Handel ein, **✕** löscht das Angebot.
- **+ Angebot erstellen:** Verkaufen/Kaufen, Spawner-Typ, Anzahl, Preis pro Stück
  (`250k`, `1,5m`, `2.000.000`), Notiz. Zeigt den aktuellen Marktpreis zur Orientierung.
- **Deals:** abgeschlossene Handel der letzten 30 Tage (Verkäufer → Käufer, Preis).
- **Preise:** pro Spawner-Typ Ø-Preis aus den Deals (nach Stückzahl gewichtet), günstigstes
  Verkaufsangebot und höchstes Ankaufsangebot. Klick zeigt die passenden Angebote.

### Links – Chats
- **Global:** alle Spieler mit der Mod, egal auf welchem Server.
- **Server:** nur Spieler auf deinem aktuellen Server.
- **Privat:** Privatchats. Klick auf einen Namen im Chat (oder „Chat“ am Angebot) öffnet einen.
- TPA-Anfragen von anderen Mod-Spielern erscheinen oben mit **Ja** (schickt `/tpaccept <Name>`).

### Rechts – Vouches
- Klick auf ein Angebot, einen Deal oder einen Namen zeigt die Vouches dieses Spielers
  (+ / −, Text, Datum) und ob er gerade online ist und wo.
- Unten Text eingeben und **+ Positiv** oder **− Negativ** klicken. Pro Spieler zählt ein Vouch
  von dir – ein neuer ersetzt den alten, **✕** an deinem Vouch löscht ihn.
- Ohne Auswahl: Rangliste der Top-Händler. Dein Kopf oben rechts zeigt deine eigenen Vouches.

### Zahnrad
Farbthema, Black Mode, Benachrichtigungen (Toast + Ton bei Privatnachricht/TPA), Deckkraft.

## Einstellungen

`config/spawner-market.json`:
- `tpaCommand` / `tpaAcceptCommand`: falls dein Server andere Befehle hat (Standard `tpa` / `tpaccept`).
- `published`: deine Angebote, Deals und Vouches; sie werden nach jedem Verbinden neu gesendet.

## Wichtig zu wissen

- Das Relay ist öffentlich und ohne Login. Die Mod prüft, dass jeder nur unter seiner eigenen UUID
  schreibt, aber **jemand mit einer modifizierten Mod kann Angebote, Deals oder Vouches fälschen**.
  Vouches und Preise sind ein Anhaltspunkt, kein Beweis – handle trotzdem vorsichtig.
- Angebote verschwinden nach 7 Tagen, Deals zählen 30 Tage, Chatverlauf reicht 6 Stunden zurück
  (pro Spieler die letzte Nachricht) plus alles, was ankommt, während du online bist.
- Preise sind in der Währung des jeweiligen Servers – darum filtert der Markt standardmäßig auf
  deinen Server.

## Bauen

```
gradlew build
```
