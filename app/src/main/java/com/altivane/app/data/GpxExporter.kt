package com.altivane.app.data

import android.content.Context
import android.os.Environment
import java.io.File
import java.time.Instant

object GpxExporter {

    fun buildGpx(
        name: String,
        pts: List<PointEntity>,
        wps: List<WaypointEntity> = emptyList()
    ): String {
        val sb = StringBuilder(pts.size * 190 + wps.size * 140 + 512)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Altivane\" ")
        sb.append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        sb.append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
        // 打卡点 waypoints
        for (w in wps) {
            sb.append("<wpt lat=\"").append("%.6f".format(w.lat))
                .append("\" lon=\"").append("%.6f".format(w.lon)).append("\">")
                .append("<ele>").append("%.1f".format(w.ele)).append("</ele>")
                .append("<time>").append(Instant.ofEpochMilli(w.t)).append("</time>")
                .append("<name>").append(xmlEscape(w.label)).append("</name>")
                .append("<type>").append(when (w.kind) {
                    "M" -> "Checkpoint"
                    "K" -> "Kilometer"
                    "C" -> "Climb"
                    "R" -> "Ski Run"
                    else -> "Waypoint"
                }).append("</type>")
                .append("</wpt>\n")
        }
        sb.append("<trk><name>").append(xmlEscape(name)).append("</name>\n<trkseg>\n")
        for (p in pts) {
            sb.append("<trkpt lat=\"").append("%.6f".format(p.lat))
                .append("\" lon=\"").append("%.6f".format(p.lon)).append("\">")
                .append("<ele>").append("%.1f".format(p.ele)).append("</ele>")
            if (p.speed > 0f) {
                sb.append("<speed>").append("%.2f".format(p.speed)).append("</speed>")
            }
            sb.append("<time>").append(Instant.ofEpochMilli(p.t)).append("</time>")
            if (p.hr > 0 || p.cad > 0) {
                sb.append("<extensions><gpxtpx:TrackPointExtension>")
                if (p.hr > 0) sb.append("<gpxtpx:hr>").append(p.hr).append("</gpxtpx:hr>")
                if (p.cad > 0) sb.append("<gpxtpx:cad>").append(p.cad).append("</gpxtpx:cad>")
                sb.append("</gpxtpx:TrackPointExtension></extensions>")
            }
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    fun save(ctx: Context, filename: String, content: String): File {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "ride")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, filename)
        f.writeText(content)
        return f
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
