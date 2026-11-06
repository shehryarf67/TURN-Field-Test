# Room labels and location pickers

The mapper packages contain two different collections: labelled room polygons and graph POIs. Several floors contain many more labelled rooms than POIs. Previously the app rendered all room labels in the image but populated the destination picker only from POIs; the current-position picker offered reference points only.

`MapperRoomCatalog` retains every source room identity. Source POIs are matched by name, with one-to-one spatial pairing for repeated names, without modifying their graph connections. Missing labels become separate Room-persisted places at their source label coordinates. A label does not imply an entrance, so these added places have no invented node or edge. Map taps use the full room polygon. Both location pickers include the floor's full place list; the current-location picker uses a room's mapped entrance and shows unavailable entrances explicitly.

Opening an existing imported floor performs an additive transaction using the source package already stored in venue notes. Repeated opens do not add duplicates; stable source IDs preserve destination renames. Graph, reference-point, survey and fingerprint rows are unchanged. No reimport or database migration is required. Source metadata is ignored if its units, axes or floor dimensions do not match the saved floor. Image-only plans still require manually registered places because image pixels contain no structured room catalogue.

For an unconnected room: Map setup → Paths → select or create a node at the physically verified entrance → Link an existing room → choose the room → connect the node to the correct corridor path → Save map. This retains the place identity and polygon while moving its route endpoint to the verified entrance. A node already assigned to a different destination cannot be overwritten by this action. Connections still pass the existing wall and walkable-space validation.

Regression checks cover all 14 bundled floor catalogues, duplicate labels, idempotent completion and rename stability; a persisted C-F5 upgrade with an existing fingerprint and unchanged graph; C-F4 reopen and room taps; searchable destination/current-location lists; and explicit linking of an existing room without duplication.

Short-window/landscape Map setup uses a single page scroll container so entrance-link controls remain reachable. Portrait retains its separate map and controls panel.

Verification (6 November 2026): 156 JVM tests passed, including all 14 supplied catalogues. All 20 selected Android cases passed across the full run and the focused corrected-selector rerun; the three new location/linking UI cases also passed in a confirmed 914 × 411 dp landscape configuration after fixing nested scrolling. The C-F5 upgrade retained its fingerprint, session, graph and destination rename after reopening Room. APK build and lint pass (zero errors, 27 warnings). The normal APK keeps the existing signing certificate.
