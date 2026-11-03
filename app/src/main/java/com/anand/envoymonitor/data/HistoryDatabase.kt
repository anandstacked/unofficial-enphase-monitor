package com.anand.envoymonitor.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.io.Writer
import java.time.Instant

@Entity(tableName = "samples", indices = [Index(value = ["gateway", "source", "device", "metric", "measuredAt"], unique = true), Index(value = ["gateway", "measuredAt"])])
data class Sample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gateway: String, val source: String, val device: String, val metric: String,
    val value: Double, val unit: String, val measuredAt: Long, val collectedAt: Long
)
data class ChartStatistics(val count: Long, val minimum: Double?, val maximum: Double?, val average: Double?)
data class CorrelationReading(val measuredAt: Long, val value: Double, val series: String)
@Dao
interface SampleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(samples: List<Sample>)
    @Query("DELETE FROM samples WHERE measuredAt < :cutoff") suspend fun prune(cutoff: Long)
    @Query("SELECT * FROM samples WHERE gateway = :gateway AND source = :source AND metric = :metric AND measuredAt >= :start AND (:device = '' OR device = :device) ORDER BY measuredAt ASC LIMIT 50000")
    fun chart(gateway: String, source: String, metric: String, start: Long, device: String = ""): Flow<List<Sample>>
    @Query("SELECT DISTINCT device FROM samples WHERE gateway = :gateway AND source = :source AND metric = :metric ORDER BY device")
    fun chartDevices(gateway: String, source: String, metric: String): Flow<List<String>>
    @Query("SELECT COUNT(*) AS count, MIN(value) AS minimum, MAX(value) AS maximum, AVG(value) AS average FROM samples WHERE gateway = :gateway AND source = :source AND metric = :metric AND device = :device AND measuredAt BETWEEN :start AND :end")
    fun chartStatistics(gateway: String, source: String, metric: String, device: String, start: Long, end: Long): Flow<ChartStatistics>
    // Each bucket retains original first/last/min/max samples. Its duration never exceeds
    // the gap threshold, so reduction cannot hide an outage within a bucket.
    @Query("""
        WITH bounded AS (
            SELECT *, measuredAt / :bucketSeconds AS bucket FROM samples WHERE gateway = :gateway AND source = :source
            AND metric = :metric AND device = :device AND measuredAt BETWEEN :start AND :end
        ), bins AS (
            SELECT bucket, MIN(measuredAt) AS firstAt,
            MAX(measuredAt) AS lastAt, MIN(value) AS low, MAX(value) AS high
            FROM bounded GROUP BY bucket
        ), picks AS (
            SELECT MIN(CASE WHEN s.measuredAt = b.firstAt THEN s.id END) AS firstId,
            MIN(CASE WHEN s.measuredAt = b.lastAt THEN s.id END) AS lastId,
            MIN(CASE WHEN s.value = b.low THEN s.id END) AS lowId,
            MIN(CASE WHEN s.value = b.high THEN s.id END) AS highId
            FROM bounded s JOIN bins b ON s.bucket = b.bucket
            GROUP BY b.bucket
        )
        SELECT * FROM samples WHERE id IN (
            SELECT firstId FROM picks UNION SELECT lastId FROM picks
            UNION SELECT lowId FROM picks UNION SELECT highId FROM picks
        ) ORDER BY measuredAt
    """)
    fun chartPoints(gateway: String, source: String, metric: String, device: String, start: Long, end: Long, bucketSeconds: Long): Flow<List<Sample>>
    @Query("""
        SELECT measuredAt, value, 'voltage' AS series FROM samples WHERE gateway=:gateway AND source='meter:net-consumption' AND metric='voltage' AND device=(SELECT device FROM samples WHERE gateway=:gateway AND source='meter:net-consumption' AND metric='voltage' ORDER BY measuredAt DESC LIMIT 1) AND measuredAt BETWEEN :start AND :end
        UNION ALL
        SELECT measuredAt, value, 'solar' AS series FROM samples WHERE gateway=:gateway AND source='meter:production' AND metric='power' AND device=(SELECT device FROM samples WHERE gateway=:gateway AND source='meter:production' AND metric='power' ORDER BY measuredAt DESC LIMIT 1) AND measuredAt BETWEEN :start AND :end
        UNION ALL
        SELECT measuredAt, value, 'grid' AS series FROM samples WHERE gateway=:gateway AND source='meter:net-consumption' AND metric='power' AND device=(SELECT device FROM samples WHERE gateway=:gateway AND source='meter:net-consumption' AND metric='power' ORDER BY measuredAt DESC LIMIT 1) AND measuredAt BETWEEN :start AND :end
        UNION ALL
        SELECT solar.measuredAt, solar.value + grid.value, 'home' AS series FROM samples solar JOIN samples grid ON grid.gateway=solar.gateway AND grid.measuredAt=solar.measuredAt
        WHERE solar.gateway=:gateway AND solar.source='meter:production' AND solar.metric='power' AND grid.source='meter:net-consumption' AND grid.metric='power' AND solar.device=(SELECT device FROM samples WHERE gateway=:gateway AND source='meter:production' AND metric='power' ORDER BY measuredAt DESC LIMIT 1) AND grid.device=(SELECT device FROM samples WHERE gateway=:gateway AND source='meter:net-consumption' AND metric='power' ORDER BY measuredAt DESC LIMIT 1) AND solar.measuredAt BETWEEN :start AND :end
        ORDER BY measuredAt
    """)
    fun voltagePowerCorrelation(gateway: String, start: Long, end: Long): Flow<List<CorrelationReading>>
    @Query("SELECT * FROM samples WHERE gateway = :gateway AND source = :source AND metric = :metric AND device = :device AND measuredAt BETWEEN :start AND :end ORDER BY ABS(measuredAt - :target), measuredAt LIMIT 1")
    suspend fun nearestChartSample(gateway: String, source: String, metric: String, device: String, start: Long, end: Long, target: Long): Sample?
    @Query("SELECT * FROM samples WHERE gateway = :gateway AND measuredAt BETWEEN :start AND :end AND id > :afterId ORDER BY id LIMIT 1000")
    suspend fun exportPage(gateway: String, start: Long, end: Long, afterId: Long): List<Sample>
    @Query("SELECT COUNT(*) FROM samples WHERE gateway = :gateway AND measuredAt BETWEEN :start AND :end")
    suspend fun exportCount(gateway: String, start: Long, end: Long): Int
    @Query("SELECT COUNT(*) FROM samples WHERE gateway = :gateway") fun count(gateway: String): Flow<Int>
    @Query("SELECT DISTINCT device FROM samples WHERE gateway = :gateway AND source = 'inverter' ORDER BY device")
    fun devices(gateway: String): Flow<List<String>>
}
@Database(entities = [Sample::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() { abstract fun samples(): SampleDao }

object HistoryMapper {
    fun samples(snapshot: Snapshot, gateway: String): List<Sample> = buildList {
        fun addReading(source: String, device: String, timestamp: Long, metric: String, value: Double?, unit: String) {
            if (timestamp > 0 && timestamp <= snapshot.receivedAt + 60 && value != null && value.isFinite()) {
                add(Sample(gateway = gateway, source = source, device = device, metric = metric, value = value,
                    unit = unit, measuredAt = timestamp, collectedAt = snapshot.receivedAt))
            }
        }
        snapshot.meters.forEach { m ->
            fun put(metric: String, value: Double?, unit: String) = addReading("meter:${m.type}", m.channelId, m.timestamp, metric, value, unit)
            put("voltage", m.voltage, "V"); put("current", m.current, "A"); put("frequency", m.frequency, "Hz")
            put("power", m.power, "W"); put("apparent_power", m.apparentPower, "VA"); put("reactive_power", m.reactivePower, "var")
            put("power_factor", m.powerFactor, "ratio"); put("delivered_energy", m.deliveredWh?.div(1000), "kWh")
            put("received_energy", m.receivedWh?.div(1000), "kWh")
        }
        snapshot.inverters.forEach { i ->
            fun put(metric: String, value: Double?, unit: String) = addReading("inverter", i.serial, i.timestamp, metric, value, unit)
            put("power", i.watts, "W"); put("ac_voltage", i.acVoltage, "V"); put("dc_voltage", i.dcVoltage, "V")
            put("ac_current", i.acCurrent, "A"); put("dc_current", i.dcCurrent, "A"); put("frequency", i.frequency, "Hz")
            put("temperature", i.temperature, "°C"); put("reported_today_energy", i.todayWh?.div(1000), "kWh")
            put("reported_yesterday_energy", i.yesterdayWh?.div(1000), "kWh"); put("reported_week_energy", i.weekWh?.div(1000), "kWh")
            put("reported_lifetime_energy", i.lifetimeWh?.div(1000), "kWh")
        }
    }
}
object CsvExporter {
    private fun cell(s: String) = "\"${s.replace("\"", "\"\"")}\""
    suspend fun write(dao: SampleDao, writer: Writer, gateway: String, start: Long, end: Long) {
        writer.write("gateway,source,device,metric,value,unit,measured_at_utc,collected_at_utc\r\n")
        var after = 0L
        while (true) {
            val page = dao.exportPage(gateway, start, end, after)
            if (page.isEmpty()) break
            page.forEach { s ->
                writer.write(listOf(s.gateway, s.source, s.device, s.metric, s.value.toString(), s.unit,
                    Instant.ofEpochSecond(s.measuredAt).toString(), Instant.ofEpochSecond(s.collectedAt).toString()).joinToString(",", transform = ::cell) + "\r\n")
            }
            after = page.last().id
        }
        writer.flush()
    }
}
