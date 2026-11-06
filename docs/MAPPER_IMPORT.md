# TURN-Mapper integration

Buildings → Import university mapper floor → C-F4 → Import. The default replacement option removes existing projects, old maps, fingerprints and linked recordings/drafts on this installation only. Export anything needed first. Leave replacement off to retain other projects. A normal restart never repeats this deletion.

The supplied single-floor packages are included as offline assets with the project owner's authorization on 2026-11-03; their original provenance text is retained. Import another single-floor VenuePackage v1 JSON through the system file picker for other buildings or newer exports. Combined multi-floor packages are rejected explicitly until multi-floor runtime support is implemented.

The importer uses supplied metric dimensions without a second Y flip. C-F4 is 56.73 × 24.819 m, with 91 nodes and 14 destinations. A display PNG is rendered from the package's room/wall geometry; graph, geometry, POIs, source JSON/revision and manual initialization anchors are stored in Room. No Wi-Fi fingerprints or positioning evidence are manufactured. Imported anchors become unsurveyed reference points. Collect a new radio survey in this coordinate frame; the old 85 × 50.8 m survey coordinates are not reused.

The C-F4 export has four nodes approximately 11 mm outside its simplified walkable contour. The adapter permits only a recorded inward alignment of nodes within 2 cm, recalculates edge lengths from the adjusted endpoints and retains the original source. TURN's existing wall/geometry/route validation remains mandatory. Larger discrepancies, invalid geometry, missing node/POI associations or unreachable destinations reject import before existing data is replaced. Floor scale is retained as supplied; measured-room calibration does not itself establish physical positioning accuracy.

Import writes a new independent project identity. Replacement and new rows commit together in one Room transaction; a database failure rolls back the replacement. No destructive schema migration or automatic database reset is introduced. Normal restarts reload the saved imported floor. Current import supports one floor at a time; it does not yet interpret the full-building connector graph.

Next priorities: multi-floor routing/position transitions, followed by refinement and physical validation of AR-assisted floor-plan generation. Existing guided AR/measured mapping stays available alongside package and image import.

Verification: 155 JVM tests and 35 Android emulator tests passed, including replacement rollback, database reopen, graph routing and the import confirmation flow. Debug app/test builds succeed; lint reports zero errors and 27 existing advisories. C-F4 was also imported and reopened through the installed app, and crowded destination labels now avoid overlap. Real-phone positioning accuracy still requires a fresh on-site survey and walking test in the imported coordinate frame.
