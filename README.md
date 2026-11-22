# APRSISOSM

Standalone Android APRS client. The first release targets station mapping, durable chats, regional offline maps, offline address search, and offline routing.

## Regional data

Each downloadable region needs a versioned manifest identifying the map archive, searchable address database, routing graph, coverage bounds, source date, attribution, and checksums. Activate a region only after all required files have been downloaded and verified. Keep the previous version available during replacement.

## Acceptance

On an Android phone in airplane mode, cold start the app, render a downloaded region with labels, find an address by street and house number, and calculate a route to it. Test a destination outside coverage and an interrupted region update. Against a local APRS-IS fixture, receive stations, open a full-SSID conversation, restart during an outstanding message, and verify deduplication and acknowledgment handling.
