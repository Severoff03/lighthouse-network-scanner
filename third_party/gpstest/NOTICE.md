# GPSTest source attribution

Upstream: https://github.com/barbeau/gpstest (master, retrieved 2026-09-25).
License: Apache-2.0; the unmodified upstream LICENSE is retained here and included
in the Android assets as gpstest-LICENSE.txt.

The original NmeaUtils.java is retained for audit. Lighthouse core GnssNmea.java
adapts its DOP indices and MSL altitude extraction, retaining attribution. Changes:
strict checksum validation, all talkers, finite-value checks, valid-fix checks,
2D/3D extraction and no dependency on Android Log/TextUtils/DilutionOfPrecision.

SatelliteStatus.kt and SharedNmeaManager.kt are reference-only upstream sources,
not compiled into Lighthouse. The GNSS lifecycle and UI were implemented for
this app, using Android GnssStatus, GPS_PROVIDER, and OnNmeaMessageListener.

Reference URLs:
- https://github.com/barbeau/gpstest/blob/master/library/src/main/java/com/android/gpstest/library/util/NmeaUtils.java
- https://github.com/barbeau/gpstest/blob/master/library/src/main/java/com/android/gpstest/library/model/SatelliteStatus.kt
- https://github.com/barbeau/gpstest/blob/master/library/src/main/java/com/android/gpstest/library/data/SharedNmeaManager.kt
