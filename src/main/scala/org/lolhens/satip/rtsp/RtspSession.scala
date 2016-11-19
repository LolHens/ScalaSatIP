package org.lolhens.satip.rtsp

import java.net.Socket
import java.nio.ByteOrder

import akka.util.ByteString
import org.lolhens.satip.rtp.RtpListener.TransmissionMode
import org.lolhens.satip.rtp.RtpListener.TransmissionMode.{Multicast, Unicast}
import org.lolhens.satip.rtsp.data.RtspVersion

/**
  * Created by pierr on 23.10.2016.
  */
class RtspSession(val rtspDevice: RtspDevice,
                  val rtspSessionId: String,
                  val rtspSessionTTL: Int = 0,
                  val rtspStreamId: String,
                  val clientRtpPort: Int,
                  val clientRtcpPort: Int,
                  val serverRtpPort: Int,
                  val serverRtcpPort: Int,
                  val rtpPort: Int,
                  val rtcpPort: Int,
                  val rtspStreamUrl: String,
                  val destination: String,
                  val source: String,
                  val transport: String,
                  val signalLevel: Int,
                  val signalQuality: Int,
                  var rtspSocket: Socket = null,
                  var rtspSequenceNum: Int = 1) {
  private var _closed = false

  def closed = _closed

  def close() = {
    _closed = true
  }

  private implicit val rtspVersion = RtspVersion(1, 0)

  def sendRequest(request: RtspRequest) = {
    if (rtspSocket == null) connect()
    val newRequest = request.copy(requestHeaders = request.requestHeaders + RtspHeaderField.CSeq(rtspSequenceNum.toString))
    rtspSequenceNum += 1
    val bytes = newRequest.toByteString
    if (rtspSocket != null) {
      val requestBytesCount = 1
      rtspSocket.getOutputStream.write(bytes.toArray)
      if (requestBytesCount < 1) ()
    }
  }

  def receiveResponse: RtspResponse = {
    val bytes = new Array[Byte](1024)
    while (rtspSocket.getInputStream.available() == 0) {
      Thread.sleep(100)
    }
    Thread.sleep(1000)
    val readBytes = rtspSocket.getInputStream.read(bytes)
    implicit val byteOrder = ByteOrder.BIG_ENDIAN
    val response = RtspResponse.fromByteString(ByteString.fromArray(bytes, 0, readBytes))
    val contentLength = response.entity.flatMap(_.entityHeaders.get(RtspHeaderField.ContentLength)).map(_.toInt)
    println(contentLength)
    contentLength.map { length =>
      val bytes = new Array[Byte](length)
      val readBytes = rtspSocket.getInputStream.read(bytes)
      val byteString = ByteString.fromArray(bytes, 0, readBytes)
      val body = byteString.utf8String
      println(body)
      body
    }
    ???
  }

  def setup(query: String, transmissionMode: TransmissionMode) = {
    //: RtspStatusCode = {
    val headers: Map[RtspHeaderField.RequestField, String] = transmissionMode match {
      case Multicast =>
        Map(RtspHeaderField.Transport(s"RTP/AVP;${transmissionMode.name.toLowerCase}"))
      case Unicast =>
        def find2FreeTcpPorts: (Int, Int) = (5555, 5556)
        val (clientRtpPort, clientRtcpPort) = find2FreeTcpPorts
        Map(RtspHeaderField.Transport(s"RTP/AVP;${transmissionMode.name.toLowerCase};client_port=$clientRtpPort-$clientRtcpPort"))
    }

    val request: RtspRequest = RtspRequest.setup(s"rtsp://${rtspDevice.serverAddress}:${554}/?$query", 0/*CSeq*/, headers)
    sendRequest(request)
    receiveResponse
    //val response: RtspResponse = ???
    //???
    //request.
  }

  def connect() = {
    rtspSocket = new Socket(rtspDevice.serverAddress, 554)
  }

  def describe() = {
    rtspSocket = new Socket("192.168.1.5", 554)
    val request = RtspRequest.describe(s"rtsp://192.168.1.5:554/stream=0", 0, Map(
      RtspHeaderField.Accept("application/sdp"),
      RtspHeaderField.Session("0")
    ), RtspEntity(Map.empty, ""))
    sendRequest(request)
    receiveResponse
  }
}

object RtspSession {
  def test = {
    val session = new RtspSession(new RtspDevice("192.168.1.5", "", "", null), "",
      0,
      "",
      0, 0, 0, 0, 0, 0,
      "", "", "",
      "", 0, 0)
    session.setup("", TransmissionMode.Unicast)
    //session.describe()
  }

  import fastparse.all._
  import org.lolhens.satip.util.ParserUtils._

  val defaultRtspSessionTTL = 30 // seconds

  val rtspSessionHeaderParser = P(s ~ (!(space | ";")).rep(min = 1).! ~ (";timeout=" ~ digits.!.map(_.toInt)).?).map {
    case (rtspSessionId, rtspSessionTTL) => (rtspSessionId, rtspSessionTTL.getOrElse(defaultRtspSessionTTL))
  }

  val describeResponseSignalInfo = P(";tuner=" ~ digits ~ "," ~ digits.!.map(_.toInt) ~ "," ~ digits.!.map(_.toInt) ~ "," ~ digits.!.map(_.toInt) ~ ",").map {
    case (level, signalLocked, quality) =>
      (signalLocked == 1, level.toDouble * 100 / 255, quality.toDouble * 100 / 255)
  }
}
