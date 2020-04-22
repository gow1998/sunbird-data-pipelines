package org.sunbird.dp.deviceprofile.functions

import java.lang.reflect.Type
import java.sql.{PreparedStatement, SQLException, Timestamp}
import java.util
import java.util.Collections

import com.google.gson.reflect.TypeToken
import com.google.gson.{Gson, JsonObject}
import org.apache.commons.lang3.StringUtils
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.postgresql.util.PGobject
import org.sunbird.dp.core.cache.{DataCache, RedisConnect}
import org.sunbird.dp.core.job.{BaseProcessFunction, Metrics}
import org.sunbird.dp.core.util.{PostgresConnect, PostgresConnectionConfig}
import org.sunbird.dp.deviceprofile.domain.DeviceProfile
import org.sunbird.dp.deviceprofile.task.DeviceProfileUpdaterConfig
import scala.collection.JavaConversions._

import scala.collection.mutable


class DeviceProfileUpdaterFunction(config: DeviceProfileUpdaterConfig,
                                   @transient var dataCache: DataCache = null,
                                   @transient var postgresConnect: PostgresConnect = null
                                  )(implicit val stringTypeInfo: TypeInformation[String])
  extends BaseProcessFunction[util.Map[String, AnyRef], util.Map[String, AnyRef]](config) {

  val mapType: Type = new TypeToken[util.Map[String, AnyRef]]() {}.getType


  override def metricsList(): List[String] = {
    List(config.deviceDbHitCount, config.cacheHitCount, config.failedEventCount, config.successCount)
  }


  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    if (dataCache == null) {
      val redisConnect = new RedisConnect(config)
      dataCache = new DataCache(config, redisConnect, config.deviceDbStore, config.fields)
      dataCache.init()
    }
    if (postgresConnect == null) {
      postgresConnect = new PostgresConnect(PostgresConnectionConfig(
        user = config.postgresUser,
        password = config.postgresPassword,
        database = config.postgresDb,
        host = config.postgresHost,
        port = config.postgresPort,
        maxConnections = config.postgresMaxConnections
      ))
    }
  }

  override def close(): Unit = {
    super.close()
    dataCache.close()
  }

  /**
   * Method to write the device profile events into redis and postgres
   *
   * @param event - Device profile events
   * @param context
   */
  override def processElement(event: util.Map[String, AnyRef],
                              context: ProcessFunction[util.Map[String, AnyRef], util.Map[String, AnyRef]]#Context,
                              metrics: Metrics): Unit = {
    if (event.size() > 0) {
      val deviceId = event.get("device_id").asInstanceOf[String]
      if (null != deviceId && !deviceId.isEmpty) {
        event.values.removeAll(Collections.singleton(""))
        event.values.removeAll(Collections.singleton("{}"))
        val deviceProfile = new DeviceProfile().fromMap(event.asInstanceOf[util.Map[String, String]], config)
        val cacheData = addDeviceDataToCache(deviceId, deviceProfile)
        addDeviceDataToDB(deviceId, event.asInstanceOf[util.Map[String, String]])
        metrics.incCounter(config.successCount)
        metrics.incCounter(config.deviceDbHitCount)
        metrics.incCounter(config.cacheHitCount)
      } else {
        metrics.incCounter(config.failedEventCount)
      }
    }
  }

  def addDeviceDataToDB(deviceId: String, deviceData: util.Map[String, String]): Unit = {
    val firstAccess: Long = deviceData.get("first_access").asInstanceOf[Number].longValue()
    val lastUpdatedDate: Long = deviceData.get("api_last_updated_on").asInstanceOf[Number].longValue()
    val parsedKeys = new util.ArrayList[String](util.Arrays.asList("first_access", "api_last_updated_on"))
    deviceData.keySet.removeAll(parsedKeys)
    val columns = String.join(",", deviceData.keySet())
    val values = StringUtils.repeat("?,", deviceData.values.size - 1);
    val postgresQuery = String.format("INSERT INTO %s (api_last_updated_on,updated_date,%s) VALUES(?,?,%s?) ON CONFLICT(device_id) DO UPDATE SET (api_last_updated_on,updated_date,%s)=(?,?,%s?);", config.postgresTable, columns, values, columns, values)
    val preparedStatement = postgresConnect.getConnection.prepareStatement(postgresQuery)
    preparedStatement.setTimestamp(1, new Timestamp(lastUpdatedDate)) // Adding api_last_updated_on as timestamp to index 1 of preparestatement

    preparedStatement.setTimestamp(deviceData.values.size + 3, new Timestamp(lastUpdatedDate)) // Adding api_last_updated_on as timestamp to 3rd index after the map size(for on conflict value)

    preparedStatement.setTimestamp(2, new Timestamp(System.currentTimeMillis)) // Adding updated_date as timestamp to index 2 of preparestatement

    preparedStatement.setTimestamp(deviceData.values.size + 4, new Timestamp(System.currentTimeMillis)) // Adding updated_date as timestamp to 4th index after the map size(for on conflict value)

    setPrepareStatement(preparedStatement, 2, deviceData.asInstanceOf[util.Map[String, String]]) // Adding map values to preparestatement from index after the api_last_updated_on and updated_on

    setPrepareStatement(preparedStatement, deviceData.values().size() + 4, deviceData.asInstanceOf[util.Map[String, String]])

    preparedStatement.executeUpdate
    preparedStatement.close()
    val updateFirstAccessQuery = String.format("UPDATE %s SET first_access = '%s' WHERE device_id = '%s' AND first_access IS NULL", config.postgresTable, new Timestamp(firstAccess).toString, deviceId)
    postgresConnect.execute(updateFirstAccessQuery)

    if (null != deviceData.get("user_declared_state")) {
      val updateUserDeclaredOnQuery = String.format("UPDATE %s SET user_declared_on = '%s' WHERE device_id = '%s' AND user_declared_on IS NULL", config.postgresTable, new Timestamp(lastUpdatedDate).toString, deviceId)
      postgresConnect.execute(updateUserDeclaredOnQuery)
    }
  }

  private def addDeviceDataToCache(deviceId: String, deviceProfile: DeviceProfile): util.Map[String, String] = {
    val deviceMap = deviceProfile.toMap(config)
    deviceMap.values.removeAll(Collections.singleton(""))
    deviceMap.values.removeAll(Collections.singleton("{}"))
    if (deviceMap.get("user_declared_state") == null) deviceMap.remove("user_declared_on")
    if (dataCache.isExists(deviceId)) {
      val redisData = dataCache.hgetAllWithRetry(deviceId)
      val firstAccess = redisData.get("firstaccess")
      val userDeclaredOn = redisData.get("user_declared_on")
      if (firstAccess != null && !("0" == firstAccess)) deviceMap.remove("firstaccess")
      if (userDeclaredOn != null && deviceMap.get("user_declared_on") != null) deviceMap.remove("user_declared_on")
      val updatedDeviceMap = updatedMissingFields(deviceMap, redisData)
      dataCache.hmSet(deviceId, updatedDeviceMap.asInstanceOf[util.Map[String, String]])
      updatedDeviceMap
    }
    else {
      dataCache.hmSet(deviceId, deviceMap.asInstanceOf[util.Map[String, String]])
      deviceMap
    }
  }

  @throws[SQLException]
  private def setPrepareStatement(preparedStatement: PreparedStatement, index: Int, deviceData: util.Map[String, String]): Unit = {

    val gson = new Gson()

    var count = index
    for (value <- deviceData.values()) {
      count += 1
      val jsonObject = new PGobject
      try {
        gson.fromJson(value, classOf[JsonObject])
        jsonObject.setType("json")
        jsonObject.setValue(gson.fromJson(value, classOf[JsonObject]).toString)
        preparedStatement.setObject(count, jsonObject)
      } catch {
        case ex: ClassCastException =>
          //ex.printStackTrace()
          preparedStatement.setString(count, value)
      }
    }
  }

  def updatedMissingFields(deviceMap: util.Map[String, String], redisData: mutable.Map[String, String]): util.Map[String, String] = {
    deviceMap.forEach((k, v) => {
      if (!redisData.contains(k)) {
        redisData.put(k, v)
      }
    })
    mapAsJavaMap(redisData)
  }
}

