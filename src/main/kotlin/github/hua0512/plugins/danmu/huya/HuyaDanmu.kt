package github.hua0512.plugins.danmu.huya

import com.qq.tars.protocol.tars.TarsInputStream
import com.qq.tars.protocol.tars.TarsOutputStream
import github.hua0512.app.App
import github.hua0512.data.DanmuData
import github.hua0512.data.Streamer
import github.hua0512.plugins.base.Danmu
import github.hua0512.plugins.danmu.huya.msg.HuyaMessageNotice
import github.hua0512.plugins.danmu.huya.msg.HuyaPushMessage
import github.hua0512.plugins.danmu.huya.msg.HuyaSocketCommand
import github.hua0512.plugins.danmu.huya.msg.HuyaUserInfo
import github.hua0512.plugins.danmu.huya.msg.data.HuyaCmds
import github.hua0512.plugins.danmu.huya.msg.data.HuyaOperations
import github.hua0512.plugins.download.Huya
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Huya danmu downloader
 * @author hua0512
 * @date : 2024/2/9 13:44
 */
class HuyaDanmu(app: App) : Danmu(app) {

  override val websocketUrl: String = "wss://cdnws.api.huya.com:443"
  override val heartBeatDelay: Long = 60.toDuration(DurationUnit.SECONDS).inWholeMilliseconds

  // @formatter:off
  override val heartBeatPack: ByteArray = byteArrayOf(0x00, 0x03, 0x1d, 0x00, 0x00, 0x69, 0x00, 0x00, 0x00, 0x69, 0x10, 0x03, 0x2c, 0x3c, 0x4c, 0x56, 0x08, 0x6f, 0x6e, 0x6c, 0x69, 0x6e, 0x65, 0x75, 0x69, 0x66, 0x0f, 0x4f, 0x6e, 0x55, 0x73, 0x65, 0x72, 0x48, 0x65, 0x61, 0x72, 0x74, 0x42, 0x65, 0x61, 0x74, 0x7d, 0x00, 0x00, 0x3c, 0x08, 0x00, 0x01, 0x06, 0x04, 0x74, 0x52, 0x65, 0x71, 0x1d, 0x00, 0x00, 0x2f, 0x0a, 0x0a, 0x0c, 0x16, 0x00, 0x26, 0x00, 0x36, 0x07, 0x61, 0x64, 0x72, 0x5f, 0x77, 0x61, 0x70, 0x46, 0x00, 0x0b, 0x12, 0x03, 0xae.toByte(), 0xf0.toByte(), 0x0f, 0x22, 0x03, 0xae.toByte(), 0xf0.toByte(), 0x0f, 0x3c, 0x42, 0x6d, 0x52, 0x02, 0x60, 0x5c, 0x60, 0x01, 0x7c, 0x82.toByte(), 0x00, 0x0b, 0xb0.toByte(), 0x1f, 0x9c.toByte(), 0xac.toByte(), 0x0b, 0x8c.toByte(), 0x98.toByte(), 0x0c, 0xa8.toByte(), 0x0c)
  // @formatter:on

  private var ayyuid: Long = 0
  private var topsid: Long = 0
  private var subid: Long = 0


  override suspend fun init(streamer: Streamer, startTime: Long): Boolean {
    this.startTime = startTime
    // check if streamer url is empty
    if (streamer.url.isEmpty()) return false
    val roomId = streamer.url.split("huya.com/")[1].split('/')[0].split('?')[0]
    val response = withContext(Dispatchers.IO) {
      app.client.get(Huya.BASE_URL + "/$roomId") {
        headers {
          Huya.platformHeaders.forEach { append(it.first, it.second) }
        }
      }
    }
    val ayyuidPattern = "yyid\":\"?(\\d+)\"?".toRegex()
    val topsidPattern = "lChannelId\":\"?(\\d+)\"?".toRegex()
    val subidPattern = "lSubChannelId\":\"?(\\d+)\"?".toRegex()

    try {
      response.bodyAsText()
    } catch (e: Exception) {
      logger.error("Failed to get huya room info: $e")
      return false
    }.also {
      ayyuid = ayyuidPattern.find(it)?.groupValues?.get(1)?.toLong() ?: 0
      topsid = topsidPattern.find(it)?.groupValues?.get(1)?.toLong() ?: 0
      subid = subidPattern.find(it)?.groupValues?.get(1)?.toLong() ?: 0
    }

    logger.info("(${streamer.name}) Huya room info: ayyuid=$ayyuid, topsid=$topsid, subid=$subid")
    return (ayyuid == 0L || topsid == 0L || subid == 0L).not().also {
      isInitialized.set(it)
    }
  }

  override fun oneHello(): ByteArray {
    val userInfo = HuyaUserInfo(
      ayyuid,
      lTid = topsid,
      lSid = subid,
    )
    // create a TarsOutputStream and write userInfo to it
    val tarsOutputStream = TarsOutputStream().also {
      userInfo.writeTo(it)
    }

    return HuyaSocketCommand(
      iCmdType = HuyaOperations.EWSCmd_RegisterReq.code,
      vData = tarsOutputStream.toByteArray()
    ).run {
      TarsOutputStream().run {
        writeTo(this)
        toByteArray()
      }
    }
  }

  override fun decodeDanmu(data: ByteArray): DanmuData? {
    val huyaSocketCommand = HuyaSocketCommand().apply {
      readFrom(TarsInputStream(data))
    }
    when (val commandType = huyaSocketCommand.iCmdType) {
      HuyaOperations.EWSCmdS2C_MsgPushReq.code -> {
        val msg = TarsInputStream(huyaSocketCommand.vData).run {
          HuyaPushMessage().apply {
            readFrom(this@run)
          }
        }
        // check if the message is a notice
        // TODO : consider supporting more cmd types
        if (msg.lUri == HuyaCmds.MessageNotice.code) {
          val msgNotice = TarsInputStream(msg.dataBytes).run {
            setServerEncoding(StandardCharsets.UTF_8.name())
            HuyaMessageNotice().apply {
              readFrom(this@run)
            }
          }
          val content = msgNotice.sContent
          return DanmuData(
            msgNotice.senderInfo.sNickName,
            color = msgNotice.tBulletFormat.iFontColor,
            content = content,
            fontSize = msgNotice.tBulletFormat.iFontSize,
            time = huyaSocketCommand.lTime.toDouble()
          ).also {
            logger.debug(it.toString())
          }
        }
      }

      else -> {
        HuyaOperations.fromCode(commandType) ?: logger.debug("Received unknown huya command: $commandType")
      }
    }
    return null
  }


}