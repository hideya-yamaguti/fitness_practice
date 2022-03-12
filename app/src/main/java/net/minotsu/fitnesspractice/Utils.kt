package net.minotsu.fitnesspractice

import com.google.android.gms.fitness.data.DataSet
import com.google.android.gms.fitness.data.DataSource
import com.google.android.gms.fitness.data.Session
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

fun toString(session: Session, dataSets: List<DataSet>): String = buildString {
    appendLine("-- SESSION --")
    appendLine("identifier:${session.identifier}")
    appendLine("name:${session.name}")
    appendLine("description:${session.description}")
    appendLine("activity:${session.activity}")
    appendLine("isOngoing:${session.isOngoing}")
    appendLine("appPackageName:${session.appPackageName}")
    appendLine("startTime:${getOffsetDateTime(session.getStartTime(TimeUnit.SECONDS))}")
    appendLine("endTime:${getOffsetDateTime(session.getEndTime(TimeUnit.SECONDS))}")
    appendLine("hasActiveTime:${session.hasActiveTime()}")

    if (session.hasActiveTime()) {
        appendLine("activeTime:${getOffsetDateTime(session.getActiveTime(TimeUnit.SECONDS))}")
    }

    if (dataSets.isNotEmpty()) {
        appendLine()
        dataSets.forEach { toString(it) }
    }
}

fun toString(dataSet: DataSet): String = buildString {
    appendLine("-- DATA SET --")
    appendLine("dataType:${dataSet.dataType}")
    appendLine()
    appendLine(toString(dataSet.dataSource))

    if (dataSet.isEmpty) {
        appendLine()
        appendLine("No data points")
    } else {
        dataSet.dataPoints.forEach {
            appendLine()
            appendLine("(DATA POINT)")
            appendLine("appPackageName:${it.originalDataSource.appPackageName}")
            appendLine("streamIdentifier:${it.dataSource.streamIdentifier}")
            appendLine("streamIdentifier(original):${it.originalDataSource.streamIdentifier}")
            appendLine("streamName(original):${it.originalDataSource.streamName}")
            appendLine("DataType:${it.dataType.name}")
            appendLine("StartTime:${getOffsetDateTime(it.getStartTime(TimeUnit.SECONDS))}")
            appendLine("EndTime:${getOffsetDateTime(it.getEndTime(TimeUnit.SECONDS))}")

            it.dataType.fields.forEach { field ->
                appendLine("${field.name}:${it.getValue(field)}")
            }
        }
    }
}

fun toString(dataSource: DataSource): String = buildString {
    dataSource.run {
        appendLine("(DATA SOURCE)")
        appendLine("packageName:$appPackageName")
        appendLine("dataType:$dataType")
        appendLine("device:$device")
        appendLine("streamIdentifier:$streamIdentifier")
        appendLine("streamName:$streamName")
        append("type:$type")
    }
}

private fun getOffsetDateTime(epochSeconds: Long): OffsetDateTime {
    return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC)
        .withOffsetSameInstant(ZoneOffset.ofHours(9))
}
