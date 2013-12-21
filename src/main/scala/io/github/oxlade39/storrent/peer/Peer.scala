package io.github.oxlade39.storrent.peer

import java.net.InetSocketAddress
import io.github.oxlade39.storrent.core.Torrent
import akka.util.ByteString
import java.util.UUID

case class Peer(address: InetSocketAddress, id: PeerId = PeerId()) {
  def hostAddress = address.getAddress.getHostAddress
  def port = address.getPort
}

case class PeerId(id: String) {
  lazy val encoded: ByteString = ByteString(id, Torrent.encoding)
}

object PeerId {
  val BITTORRENT_ID_PREFIX = "-TO0042-"

  def randomId = BITTORRENT_ID_PREFIX + UUID.randomUUID().toString.split("-")(4)

  def apply(id: ByteString): PeerId = PeerId(id.decodeString(Torrent.encoding))
  def apply(): PeerId = PeerId(randomId)
}