# Hikari Desktop MVP design direction

Reading this as: a Windows desktop media browser for existing Hikari users, carrying the established Hikari AMOLED navy surfaces and amber accent, with ENERGY 2 / RHYTHM 2 / MOTION 1.

- The navy background and amber accent reuse Hikari's existing `HikariBg`, `HikariSurface`, and `HikariPrimary` colors. They preserve product identity and keep artwork prominent.
- A desktop navigation rail groups Home, Search, Library, History, and installed sources because each is a real destination.
- Library shows compact local title snapshots as posters, newest first, with direct removal. Category chips narrow the saved grid, assignment stays beside each poster, and category maintenance sits in a separate dialog so it does not crowd browsing. Move up/Move down controls make saved category order explicit and keyboard-operable. Its empty-state action leads back to Home.
- History uses a compact poster-and-action list for the decisions it supports: reopen details, request the same stream again, or remove a record. It shows successful launch time only because the desktop app cannot read playback progress from an external player.
- Content is organized around catalog rows and title posters, matching the media-browsing task instead of a generic dashboard layout.
- Home keeps a bounded set of shelves and opens the complete catalog directory on demand, so every installed catalog is discoverable without making the primary rail longer.
- Search results use compact All/Movies/Series chips only when results exist; amber marks the active local type filter using the same primary accent as the existing catalog filters.
- Search source scope reuses Home's provider dropdown so all, one, or a selected group of add-ons stays compact as the installed source list grows.
- System sans-serif typography keeps long titles and metadata readable at Windows desktop sizes.
- Motion is limited to hover and selection feedback so it does not compete with artwork or streaming state.
- The wordmark is rendered as text. No new logo, portraits, or decorative artwork are introduced.
- Recent searches appears only when the query is empty, so a past query can be repeated or removed without competing with active results.
