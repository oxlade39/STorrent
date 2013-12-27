package io.github.oxlade39.storrent.peer

import org.scalatest.{WordSpec, FunSuite}
import akka.util.ByteString
import org.scalatest.matchers.MustMatchers
import scala.util.Random

class HandshakeTest extends WordSpec with MustMatchers {
  "Handshake" must {
    "parse a valid ByteString" in {
      val hashBytes = new Array[Byte](20)
      Random.nextBytes(hashBytes)
      val infoHash: ByteString = ByteString(hashBytes)
      val peerId = PeerId()

      val bs = Handshake(infoHash, peerId).encoded
      val parsed: Option[Handshake] = Handshake.parse(bs)

      parsed mustEqual Some(Handshake(infoHash, peerId))
    }

    "not parse an invalid length hash" in {
      val hashBytes = new Array[Byte](19)
      Random.nextBytes(hashBytes)
      val infoHash: ByteString = ByteString(hashBytes)
      val peerId = PeerId()

      val bs = Handshake(infoHash, peerId).encoded
      val parsed: Option[Handshake] = Handshake.parse(bs)

      parsed mustEqual None
    }

    "not parse an invalid length peerId" in {
      val hashBytes = new Array[Byte](20)
      Random.nextBytes(hashBytes)
      val infoHash: ByteString = ByteString(hashBytes)
      val peerId = PeerId("3849384903")

      val bs = Handshake(infoHash, peerId).encoded
      val parsed: Option[Handshake] = Handshake.parse(bs)

      parsed mustEqual None
    }
  }
}
