# TURN data dictionary

All distances and coordinates use metres. Screen pixels are never persisted as map coordinates. Timestamps use Unix epoch milliseconds unless the export explicitly states ISO-8601.

## Identity and map records

| Record | Important fields | Meaning |
| --- | --- | --- |
| Venue | `id`, `name`, `createdAt` | One authorized pilot building. |
| Floor | `id`, `venueId`, `levelNumber`, `name`, `widthM`, `heightM`, `origin` | Metric coordinate frame for one level. |
| FloorPlanAsset | `id`, `floorId`, `uri`, `pixelWidth`, `pixelHeight`, calibration fields | Visual background and pixel-to-metre transform. |
| WalkableRegion | `id`, `floorId`, `verticesJson` | Polygon in metric coordinates where a person may stand. |
| WallSegment | `id`, `floorId`, `x1`, `y1`, `x2`, `y2` | Blocking metric segment. |
| VerticalTransition | `id`, `floorId`, `kind`, `x`, `y`, `linkedTransitionId`, `accessible` | Stair/lift/escalator endpoint. |
| PointOfInterest | `id`, `floorId`, `name`, `category`, `x`, `y` | Editable destination metadata. |
| ReferencePoint | `id`, `floorId`, `label`, `x`, `y`, `enabled` | Training coordinate used for fingerprint collection. |
| QrAnchor | `id`, `floorId`, `label`, `x`, `y`, `initialHeadingRad` | Trusted explicit position fix. |
| TestCheckpoint | `id`, `floorId`, `label`, `x`, `y` | Independent evaluation truth; never a training key. |

`verticesJson` is an ordered JSON array of `{x,y}` objects. Heading is radians in the application's documented map convention.

## Survey and radio records

| Record | Important fields | Meaning |
| --- | --- | --- |
| SurveySession | `id`, `referencePointId`, `deviceId`, `startedAt`, `orientation`, `crowdCondition`, `notes`, `throttlingDisabled` | One bounded collection at known truth. |
| WifiScanSnapshot | `id`, `surveySessionId`, `requestedAt`, `receivedAt`, `resultsUpdated`, `freshness`, `requestAccepted` | One Android scan result set, including failures/cached sets. |
| WifiObservation | `id`, `snapshotId`, `bssid`, `ssid`, `rssiDbm`, `frequencyMhz`, `observedAt`, `excluded` | One raw AP reading. SSID may be omitted from export. |
| AggregatedWifiFingerprint | `referencePointId`, `bssid`, `medianDbm`, `meanDbm`, `stdDevDb`, `minDbm`, `maxDbm`, `sampleCount`, `detectionRate`, `excluded` | Rebuildable training feature. |
| BeaconDefinition | `id`, `venueId`, `protocol`, `stableId`, optional coordinates, `enabled` | Future fixed-beacon inventory. |
| BleObservation | `id`, `sessionId`, `stableId`, `rssiDbm`, `txPower`, `observedAt`, raw service/manufacturer data | Future raw advertisement. |
| AggregatedBleFingerprint | reference/beacon key plus RSSI statistics | Future BLE feature, never required for Wi-Fi. |

BSSID/stable beacon IDs are case-normalized. Missing RSSI is an algorithm configuration, not a fabricated observation row.

## Motion and positioning records

| Record | Important fields | Meaning |
| --- | --- | --- |
| SensorSession | `id`, `deviceId`, source availability, stride parameters, heading source, start/end | Hardware/provenance for one motion run. |
| PdrEvent | `id`, `sensorSessionId`, `timestamp`, `kind`, `stepDelta`, `headingRad`, `pressureHpa`, `accepted`, `reason` | Raw/derived motion event, including rejected duplicates. |
| PositioningSession | `id`, `venueId`, `mode`, `startedAt`, `initializationSource`, configuration snapshot | Reproducible live run. |
| PositionEstimate | `id`, `positioningSessionId`, `timestamp`, `method`, `x`, `y`, `floorId`, `confidenceRadiusM`, `floorConfidence`, `sourceAgeMs`, `status` | Output from Wi-Fi, PDR, fused or stabilized method. |
| CorrectionEvent | `id`, `positioningSessionId`, `timestamp`, `type`, pre/post state, `details` | Wi-Fi, QR, map rejection, relocalization or failure evidence. |

`method` is one of `WIFI_ONLY`, `RAW_PDR`, `FUSED`, or a future `STABILIZED` output. Ground truth fields do not exist on `PositionEstimate`.

## Evaluation records

| Record | Important fields | Meaning |
| --- | --- | --- |
| TestRun | `id`, `venueId`, `deviceId`, `startedAt`, protocol/configuration fields | Frozen independent experiment. |
| TestSample | `id`, `testRunId`, `checkpointId`, `estimateId`, `capturedAt`, `horizontalErrorM`, `floorCorrect`, `insideConfidence`, `latencyMs`, radio/sensor counts | Grade assigned after linking an estimate to known checkpoint truth. |

Exports must preserve method and session IDs so Wi-Fi-only, PDR-only and fused samples from the same instant can be paired without pooling away device/run differences.

## Null and privacy conventions

- Nullable means “not observed/not applicable,” never zero.
- `SSID`, notes and floor-plan URI can be removed from shared exports.
- No participant name, email, phone number or account identifier is collected.
- Device ID is an app-generated research label, not a hardware serial or advertising ID.
